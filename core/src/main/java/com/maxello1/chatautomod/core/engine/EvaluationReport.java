package com.maxello1.chatautomod.core.engine;

import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.PreventedMatch;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.score.ThresholdCrossing;

import java.util.List;
import java.util.Optional;

public record EvaluationReport(
        MessageDecision decision,
        Optional<NormalizedMessage> normalizedMessage,
        List<RuleMatch> matches,
        int pointsBefore,
        int pointsAdded,
        int pointsAfter,
        Optional<ThresholdCrossing> thresholdCrossing,
        List<ModerationAction> actionsThatWouldRun,
        List<PreventedMatch> preventedMatches
) {
    public EvaluationReport {
        normalizedMessage = normalizedMessage == null ? Optional.empty() : normalizedMessage;
        matches = List.copyOf(matches);
        thresholdCrossing = thresholdCrossing == null ? Optional.empty() : thresholdCrossing;
        actionsThatWouldRun = List.copyOf(actionsThatWouldRun);
        preventedMatches = List.copyOf(preventedMatches);
    }

    public EvaluationReport(MessageDecision decision,
            Optional<NormalizedMessage> normalizedMessage, List<RuleMatch> matches,
            int pointsBefore, int pointsAdded, int pointsAfter,
            Optional<ThresholdCrossing> thresholdCrossing,
            List<ModerationAction> actionsThatWouldRun) {
        this(decision, normalizedMessage, matches, pointsBefore, pointsAdded,
                pointsAfter, thresholdCrossing, actionsThatWouldRun, List.of());
    }
}
