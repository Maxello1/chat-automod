package com.maxello1.chatautomod.core.engine;

import com.maxello1.chatautomod.core.action.ActionPlan;
import com.maxello1.chatautomod.core.action.ActionPlanner;
import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.action.RuleEffect;
import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.detector.DetectorRegistry;
import com.maxello1.chatautomod.core.detector.FilterDetector;
import com.maxello1.chatautomod.core.detector.MessageDetector;
import com.maxello1.chatautomod.core.model.MuteState;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.PreventedMatch;
import com.maxello1.chatautomod.core.model.RecentMessage;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.model.ViolationRecord;
import com.maxello1.chatautomod.core.normalize.TextNormalizer;
import com.maxello1.chatautomod.core.score.ScoreCalculator;
import com.maxello1.chatautomod.core.score.ScoreContribution;
import com.maxello1.chatautomod.core.score.ScoreMath;
import com.maxello1.chatautomod.core.score.ScoreTransition;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ModerationEngine {
    private final List<MessageDetector> detectors;
    private final MatchDeduplicator deduplicator = new MatchDeduplicator();
    private final ScoreCalculator scoreCalculator = new ScoreCalculator();
    private final ActionPlanner actionPlanner = new ActionPlanner();

    public ModerationEngine() {
        this(DetectorRegistry.standard());
    }

    public ModerationEngine(List<MessageDetector> detectors) {
        this.detectors = List.copyOf(detectors);
    }

    EvaluationDraft plan(MessageContext context, PlayerModerationState state, BypassProfile bypass,
            CompiledAutoModConfig config) {
        if (!config.enabled() || bypass.all()) return unchanged(context, state, MessageDecision.ALLOW);
        Optional<MuteState> activeMute = state.mute().filter(mute -> mute.activeAt(context.timestamp()));
        if (activeMute.isPresent()) return muted(context, state, activeMute.get(), config);

        NormalizedMessage normalized = new TextNormalizer(config.normalization()).normalize(context.rawMessage());
        List<RuleMatch> rawMatches = new ArrayList<>();
        List<PreventedMatch> preventedMatches = new ArrayList<>();
        for (MessageDetector detector : detectors) {
            if (detector instanceof FilterDetector filterDetector) {
                if (!bypass.bypasses(RuleCategory.FILTERED_CONTENT)) {
                    FilterDetector.DetectionResult result =
                            filterDetector.detectDetailed(context, config);
                    result.matches().stream()
                            .filter(match -> !bypass.bypasses(match.category()))
                            .forEach(rawMatches::add);
                    preventedMatches.addAll(result.preventedMatches());
                }
            } else if (!bypass.bypasses(detector.category())) {
                rawMatches.addAll(detector.detect(context, normalized, state, config));
            }
        }
        List<RuleMatch> matches = deduplicator.deduplicate(rawMatches);
        List<ScoreContribution> contributions = matches.stream()
                .map(match -> new ScoreContribution(match.ruleId(), match.points())).toList();
        ScoreTransition score;
        try {
            score = scoreCalculator.calculate(context.timestamp(), state.scoreEntries(), contributions, config.score(),
                    config.state().maximumScoreEntriesPerPlayer());
        } catch (ScoreCalculator.StateCapacityException exception) {
            RuleMatch capacity = new RuleMatch("security.state_capacity", RuleCategory.SECURITY, 0,
                    exception.getMessage(), "", new RuleEffect(true, List.of(com.maxello1.chatautomod.core.action.ConfiguredAction.notifyStaff())));
            matches = new ArrayList<>(matches);
            matches.add(capacity);
            score = scoreCalculator.calculate(context.timestamp(), state.scoreEntries(), List.of(), config.score(),
                    config.state().maximumScoreEntriesPerPlayer());
        }
        ActionPlan plan = actionPlanner.plan(context, matches, score, config.staffAlerts().showOriginal());
        PlayerModerationState nextState = nextState(context, state, normalized, matches, score, plan, config);
        EvaluationReport report = new EvaluationReport(plan.decision(), Optional.of(normalized), matches,
                score.pointsBefore(), score.pointsAdded(), score.pointsAfter(),
                score.crossing(), plan.actions(), preventedMatches);
        return new EvaluationDraft(report, plan, nextState);
    }

    private EvaluationDraft unchanged(MessageContext context, PlayerModerationState state, MessageDecision decision) {
        int score = ScoreMath.sumActive(state.scoreEntries(), context.timestamp());
        EvaluationReport report = new EvaluationReport(decision, Optional.empty(), List.of(), score, 0, score,
                Optional.empty(), List.of());
        return new EvaluationDraft(report, new ActionPlan(decision, List.of()), state);
    }

    private EvaluationDraft muted(MessageContext context, PlayerModerationState state, MuteState mute,
            CompiledAutoModConfig config) {
        boolean notify = state.lastMuteNotificationAt().map(last -> !last.plus(config.mutes().notificationCooldown())
                .isAfter(context.timestamp())).orElse(true);
        String notice = mute.kind() == com.maxello1.chatautomod.core.model.MuteKind.PERMANENT
                ? "You are permanently muted. Reason: " + mute.reason()
                : "You are muted for another "
                        + formatRemaining(Duration.between(context.timestamp(), mute.mutedUntil()))
                        + ". Reason: " + mute.reason();
        List<ModerationAction> actions = notify
                ? List.of(new ModerationAction.Warn(context.playerId(), notice)) : List.of();
        PlayerModerationState next = notify
                ? new PlayerModerationState(state.revision() + 1, state.playerId(),
                        context.playerName(), state.recentMessageTimes(),
                        state.recentMessages(), state.scoreEntries(),
                        state.crossedThresholds(), state.mute(), state.violations(),
                        Optional.of(context.timestamp()), context.timestamp())
                : state;
        int points = ScoreMath.sumActive(state.scoreEntries(), context.timestamp());
        EvaluationReport report = new EvaluationReport(MessageDecision.BLOCK, Optional.empty(), List.of(), points, 0,
                points, Optional.empty(), actions);
        return new EvaluationDraft(report, new ActionPlan(MessageDecision.BLOCK, actions), next);
    }

    private PlayerModerationState nextState(MessageContext context, PlayerModerationState state,
            NormalizedMessage normalized, List<RuleMatch> matches, ScoreTransition score, ActionPlan plan,
            CompiledAutoModConfig config) {
        List<Instant> times = new ArrayList<>();
        Instant rapidCutoff = context.timestamp().minus(config.rules().rapidSpam().window());
        state.recentMessageTimes().stream().filter(time -> !time.isBefore(rapidCutoff)).forEach(times::add);
        times.add(context.timestamp());
        trimFront(times, config.rules().rapidSpam().maximumMessages());

        Duration messageWindow = config.rules().duplicateSpam().window().compareTo(config.rules().similaritySpam().window()) >= 0
                ? config.rules().duplicateSpam().window() : config.rules().similaritySpam().window();
        Instant messageCutoff = context.timestamp().minus(messageWindow);
        List<RecentMessage> recent = new ArrayList<>();
        state.recentMessages().stream().filter(message -> !message.timestamp().isBefore(messageCutoff)).forEach(recent::add);
        String similarity = truncate(normalized.deobfuscated(), config.rules().similaritySpam().maximumProcessedLength());
        recent.add(new RecentMessage(normalized.canonical(), similarity, context.timestamp()));
        trimFront(recent, Math.max(config.rules().duplicateSpam().historySize(), config.rules().similaritySpam().historySize()));

        Optional<MuteState> mute = state.mute()
                .filter(existing -> existing.activeAt(context.timestamp()));
        for (ModerationAction action : plan.actions()) {
            if (!(action instanceof ModerationAction.Mute requested)) {
                continue;
            }
            if (requested.kind() == com.maxello1.chatautomod.core.model.MuteKind.PERMANENT) {
                mute = Optional.of(MuteState.permanent(requested.mutedAt(),
                        requested.reason(), requested.source(), requested.ruleId(), null));
            } else if (mute.isEmpty()
                    || mute.orElseThrow().kind()
                            != com.maxello1.chatautomod.core.model.MuteKind.PERMANENT
                    && mute.orElseThrow().mutedUntil().isBefore(requested.until())) {
                mute = Optional.of(MuteState.temporary(requested.mutedAt(),
                        requested.until(), requested.reason(), requested.source(),
                        requested.ruleId(), null));
            }
        }

        List<ViolationRecord> violations = new ArrayList<>();
        Instant historyCutoff = context.timestamp().minus(Duration.ofDays(config.history().retentionDays()));
        state.violations().stream().filter(record -> !record.timestamp().isBefore(historyCutoff)).forEach(violations::add);
        if (!matches.isEmpty()) {
            Optional<String> original = config.logging().storeOriginalMessages()
                    ? Optional.of(sanitizeStored(context.rawMessage()))
                    : Optional.empty();
            com.maxello1.chatautomod.core.model.Severity severity = matches.stream()
                    .map(RuleMatch::severity)
                    .max((left, right) -> Integer.compare(left.ordinal(), right.ordinal()))
                    .orElse(com.maxello1.chatautomod.core.model.Severity.LOW);
            Optional<com.maxello1.chatautomod.core.model.MuteKind> muteKind =
                    plan.actions().stream()
                            .filter(ModerationAction.Mute.class::isInstance)
                            .map(ModerationAction.Mute.class::cast)
                            .map(ModerationAction.Mute::kind)
                            .findFirst();
            violations.add(new ViolationRecord(UUID.randomUUID(),
                    context.timestamp(), context.playerId(), context.playerName(),
                    matches.stream().map(RuleMatch::ruleId).toList(),
                    matches.stream().map(RuleMatch::category).distinct().toList(),
                    severity, plan.decision(), score.pointsAdded(), score.pointsAfter(),
                    plan.actions().stream().map(ModerationAction::type)
                            .distinct().toList(),
                    muteKind, original));
        }
        trimFront(violations, config.history().maximumEntriesPerPlayer());
        java.util.Set<Integer> crossedThresholds = config.score().thresholds().stream()
                .map(CompiledAutoModConfig.Threshold::points)
                .filter(points -> score.pointsAfter() >= points)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new PlayerModerationState(state.revision() + 1,
                context.playerId(), context.playerName(), times, recent,
                score.entries(), crossedThresholds, mute, violations,
                state.lastMuteNotificationAt(), context.timestamp());
    }

    private String sanitizeStored(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\r' || codePoint == '\n' || codePoint == 0x2028 || codePoint == 0x2029) {
                sanitized.append(' ');
            } else if (!Character.isISOControl(codePoint)) {
                sanitized.appendCodePoint(codePoint);
            }
        });
        return sanitized.toString();
    }

    private static <T> void trimFront(List<T> values, int maximum) {
        if (values.size() > maximum) values.subList(0, values.size() - maximum).clear();
    }

    private static String truncate(String value, int maximum) {
        return value.codePointCount(0, value.length()) <= maximum ? value
                : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static String formatRemaining(Duration duration) {
        long seconds = Math.max(1, duration.toSeconds());
        if (seconds >= 3600) return (seconds / 3600) + "h";
        if (seconds >= 60) return (seconds / 60) + "m";
        return seconds + "s";
    }
}
