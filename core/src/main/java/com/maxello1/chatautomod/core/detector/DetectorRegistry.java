package com.maxello1.chatautomod.core.detector;

import java.util.List;

public final class DetectorRegistry {
    private DetectorRegistry() {}

    public static List<MessageDetector> standard() {
        return List.of(new MessageLengthDetector(), new CapsDetector(), new RepeatedCharacterDetector(),
                new ExactDuplicateDetector(), new RapidSpamDetector(), new SimilaritySpamDetector(),
                new FilterDetector(), new AdvertisingDetector());
    }
}
