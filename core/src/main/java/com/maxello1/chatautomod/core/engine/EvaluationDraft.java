package com.maxello1.chatautomod.core.engine;

import com.maxello1.chatautomod.core.action.ActionPlan;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

record EvaluationDraft(EvaluationReport report, ActionPlan actionPlan, PlayerModerationState nextState) {}
