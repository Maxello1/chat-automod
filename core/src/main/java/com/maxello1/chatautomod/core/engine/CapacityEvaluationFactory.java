package com.maxello1.chatautomod.core.engine;

import com.maxello1.chatautomod.core.action.ActionPlan;
import com.maxello1.chatautomod.core.action.ConfiguredAction;
import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.action.RuleEffect;
import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;

import java.util.List;
import java.util.Optional;

final class CapacityEvaluationFactory {
    private CapacityEvaluationFactory() {}

    static LiveEvaluation create(MessageContext context, CompiledAutoModConfig config) {
        RuleMatch match = new RuleMatch("security.state_capacity", RuleCategory.SECURITY, 0,
                "maximum tracked players reached", "", new RuleEffect(true, List.of(ConfiguredAction.notifyStaff())));
        ModerationAction.NotifyStaff alert = new ModerationAction.NotifyStaff(context.playerId(), context.playerName(),
                List.of(match.ruleId()), MessageDecision.BLOCK,
                config.staffAlerts().showOriginal() ? context.rawMessage() : "", 0, 0, context.timestamp());
        ActionPlan plan = new ActionPlan(MessageDecision.BLOCK, List.of(alert));
        EvaluationReport report = new EvaluationReport(MessageDecision.BLOCK, Optional.empty(), List.of(match),
                0, 0, 0, Optional.empty(), plan.actions());
        return new LiveEvaluation(report, plan);
    }
}
