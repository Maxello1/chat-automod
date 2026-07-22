package com.maxello1.chatautomod.core.score;

import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;

public record ThresholdCrossing(int points, CompiledAutoModConfig.Threshold threshold) {}
