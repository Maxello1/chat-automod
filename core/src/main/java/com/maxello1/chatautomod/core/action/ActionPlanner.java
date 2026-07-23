package com.maxello1.chatautomod.core.action;

import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.score.ScoreTransition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ActionPlanner {
    public ActionPlan plan(MessageContext context, List<RuleMatch> matches, ScoreTransition score, boolean showOriginalInAlerts) {
        MessageDecision decision = matches.stream().anyMatch(match -> match.effect().blockMessage())
                ? MessageDecision.BLOCK : MessageDecision.ALLOW;
        LinkedHashSet<ConfiguredAction> configured = new LinkedHashSet<>();
        matches.forEach(match -> configured.addAll(match.effect().directActions()));
        score.crossing().ifPresent(crossing -> configured.addAll(crossing.threshold().actions()));

        List<String> ruleIds = matches.stream().map(RuleMatch::ruleId).distinct().toList();
        String primaryRule = ruleIds.isEmpty() ? "none" : ruleIds.get(0);
        boolean notifyStaff = configured.stream().anyMatch(action -> action.type() == ActionType.NOTIFY_STAFF);
        Set<ModerationAction> actions = new LinkedHashSet<>();
        ModerationAction.Mute strongestMute = null;
        for (ConfiguredAction action : configured) {
            switch (action.type()) {
                case NOTIFY_STAFF -> { }
                case WARN -> actions.add(new ModerationAction.Warn(context.playerId(), action.message()));
                case MUTE -> {
                    com.maxello1.chatautomod.core.model.MuteKind kind =
                            action.duration() == null
                                    ? com.maxello1.chatautomod.core.model.MuteKind.PERMANENT
                                    : com.maxello1.chatautomod.core.model.MuteKind.TEMPORARY;
                    Instant until = action.duration() == null ? null
                            : safePlus(context.timestamp(), action.duration());
                    ModerationAction.Mute candidate = new ModerationAction.Mute(
                            context.playerId(), kind, context.timestamp(), until,
                            action.reason(), "automatic", primaryRule);
                    if (strongestMute == null
                            || candidate.kind() == com.maxello1.chatautomod.core.model.MuteKind.PERMANENT
                            || strongestMute.kind()
                                    != com.maxello1.chatautomod.core.model.MuteKind.PERMANENT
                                    && candidate.until().isAfter(strongestMute.until())) {
                        strongestMute = candidate;
                    }
                }
                case KICK -> actions.add(new ModerationAction.Kick(context.playerId(), action.reason()));
                case EXECUTE_COMMAND -> actions.add(new ModerationAction.ExecuteCommand(action.command().expand(
                        context.playerName(), context.playerId().toString(), score.pointsAfter(), primaryRule)));
                case REPLACE_MATCH, REBROADCAST_MODIFIED, DISCORD_WEBHOOK -> throw new IllegalStateException("reserved action is not executable");
            }
        }
        if (strongestMute != null) {
            actions.add(strongestMute);
        }
        if (notifyStaff) {
            actions.add(new ModerationAction.NotifyStaff(context.playerId(), context.playerName(), ruleIds,
                    decision, showOriginalInAlerts ? context.rawMessage() : "", score.pointsAdded(), score.pointsAfter(),
                    context.timestamp()));
        }
        return new ActionPlan(decision, new ArrayList<>(actions));
    }

    private Instant safePlus(Instant instant, java.time.Duration duration) {
        try { return instant.plus(duration); }
        catch (java.time.DateTimeException | ArithmeticException exception) { return Instant.MAX; }
    }
}
