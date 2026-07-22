package com.maxello1.chatautomod.core.detector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BoundedDamerauLevenshteinTest {
    @Test
    void countsAdjacentTranspositionAsOneEdit() {
        assertEquals(1, BoundedDamerauLevenshtein.distance("distance", "ditsance", 1));
    }

    @Test
    void returnsOnePastTheBoundWhenNoCandidateCanRecover() {
        assertEquals(3, BoundedDamerauLevenshtein.distance("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", 2));
    }

    @Test
    void handlesUnequalLengthsInsideAndOutsideTheBand() {
        assertEquals(2, BoundedDamerauLevenshtein.distance("chat", "chatty", 2));
        assertEquals(3, BoundedDamerauLevenshtein.distance("chat", "chatter", 2));
        assertEquals(3, BoundedDamerauLevenshtein.distance("chatter", "chat", 2));
    }
}
