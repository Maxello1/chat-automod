package com.maxello1.chatautomod.core.detector;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class SimilarityCalculator {
    public boolean similar(String left, String right, double threshold) {
        if (left.equals(right)) return true;
        int leftLength = left.codePointCount(0, left.length());
        int rightLength = right.codePointCount(0, right.length());
        int maximumLength = Math.max(leftLength, rightLength);
        if (maximumLength == 0) return true;
        double lengthRatio = (double) Math.min(leftLength, rightLength) / maximumLength;
        if (lengthRatio < 0.6) return false;
        double tokenContainment = tokenContainment(left, right);
        if (tokenContainment >= threshold && Math.min(leftLength, rightLength) >= 4) return true;
        if (lengthRatio < threshold) return false;
        int maximumDistance = (int) Math.floor((1.0 - threshold) * maximumLength);
        int distance = BoundedDamerauLevenshtein.distance(left, right, maximumDistance);
        return distance <= maximumDistance;
    }

    private double tokenContainment(String left, String right) {
        Set<String> a = tokens(left);
        Set<String> b = tokens(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        long intersection = a.stream().filter(b::contains).count();
        return (double) intersection / Math.min(a.size(), b.size());
    }

    private Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        Arrays.stream(value.split("[^\\p{L}\\p{N}]+"))
                .filter(token -> !token.isBlank()).forEach(result::add);
        return result;
    }
}
