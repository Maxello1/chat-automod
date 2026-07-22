package com.maxello1.chatautomod.core.engine;

import com.maxello1.chatautomod.core.action.ActionPlan;
import com.maxello1.chatautomod.core.action.ActionPreview;
import com.maxello1.chatautomod.core.action.ConfiguredAction;
import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.action.RuleEffect;
import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.state.PlayerModerationState;
import com.maxello1.chatautomod.core.state.PlayerStateStore;
import com.maxello1.chatautomod.core.state.StateUpdate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ModerationService {
    private final ActiveConfig configs;
    private final PlayerStateStore states;
    private final ModerationEngine engine;

    public ModerationService(ActiveConfig configs, PlayerStateStore states) {
        this(configs, states, new ModerationEngine());
    }

    public ModerationService(ActiveConfig configs, PlayerStateStore states, ModerationEngine engine) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.states = Objects.requireNonNull(states, "states");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public LiveEvaluation evaluateLive(MessageContext context, BypassProfile bypass) {
        if (context.testMode()) throw new IllegalArgumentException("live evaluation requires testMode=false");
        var config = configs.current();
        BypassProfile effectiveBypass = bypass == null ? BypassProfile.NONE : bypass;
        if (!config.enabled() || effectiveBypass.all()) {
            PlayerModerationState snapshot = states.snapshot(context.playerId(), context.playerName(), context.timestamp());
            EvaluationDraft draft = engine.plan(context, snapshot, effectiveBypass, config);
            return new LiveEvaluation(draft.report(), draft.actionPlan());
        }
        java.util.function.Function<PlayerModerationState, StateUpdate<LiveEvaluation>> evaluation = current -> {
            EvaluationDraft draft = engine.plan(context, current, effectiveBypass, config);
            return new StateUpdate<>(draft.nextState(), new LiveEvaluation(draft.report(), draft.actionPlan()));
        };
        var result = states.transactIfCapacity(context.playerId(), context.playerName(), context.timestamp(),
                config.state().maximumTrackedPlayers(), evaluation);
        if (result.isEmpty()) {
            states.pruneInactive(context.timestamp(), config.state().inactivePlayerTime(),
                    java.time.Duration.ofDays(config.history().retentionDays()), config.state().maximumTrackedPlayers());
            result = states.transactIfCapacity(context.playerId(), context.playerName(), context.timestamp(),
                    config.state().maximumTrackedPlayers(), evaluation);
        }
        return result.orElseGet(() -> CapacityEvaluationFactory.create(context, config));
    }

    public PreviewEvaluation preview(MessageContext context, BypassProfile bypass) {
        PlayerModerationState snapshot = states.snapshot(context.playerId(), context.playerName(), context.timestamp());
        return preview(context, snapshot, bypass);
    }

    public PreviewEvaluation preview(MessageContext context, PlayerModerationState detachedState, BypassProfile bypass) {
        MessageContext testContext = context.testMode() ? context : context.asTest();
        EvaluationDraft draft = engine.plan(testContext, detachedState, bypass == null ? BypassProfile.NONE : bypass,
                configs.current());
        return new PreviewEvaluation(draft.report(), new ActionPreview(draft.actionPlan().decision(), draft.actionPlan().actions()));
    }
}
