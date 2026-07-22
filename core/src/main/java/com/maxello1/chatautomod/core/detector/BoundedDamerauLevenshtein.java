package com.maxello1.chatautomod.core.detector;

public final class BoundedDamerauLevenshtein {
    private BoundedDamerauLevenshtein() {}

    public static int distance(String left, String right, int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("maximum must not be negative");
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        int overLimit = maximum == Integer.MAX_VALUE ? Integer.MAX_VALUE : maximum + 1;
        if (Math.abs((long) a.length - b.length) > maximum) return overLimit;

        int[] previousPrevious = new int[b.length + 1];
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        int initialLastColumn = Math.min(b.length, maximum);
        for (int j = 0; j <= initialLastColumn; j++) previous[j] = j;
        if (initialLastColumn < b.length) previous[initialLastColumn + 1] = overLimit;

        for (int i = 1; i <= a.length; i++) {
            current[0] = i <= maximum ? i : overLimit;
            int rowMinimum = current[0];
            int firstColumn = Math.max(1, i - maximum);
            int lastColumn = (int) Math.min(b.length, (long) i + maximum);
            if (firstColumn > 1) current[firstColumn - 1] = overLimit;
            for (int j = firstColumn; j <= lastColumn; j++) {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                int value = Math.min(Math.min(increment(current[j - 1], overLimit),
                        increment(previous[j], overLimit)), add(previous[j - 1], cost, overLimit));
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = Math.min(value, increment(previousPrevious[j - 2], overLimit));
                }
                current[j] = value;
                rowMinimum = Math.min(rowMinimum, value);
            }
            if (lastColumn < b.length) current[lastColumn + 1] = overLimit;
            if (rowMinimum > maximum) return overLimit;
            int[] reusable = previousPrevious;
            previousPrevious = previous;
            previous = current;
            current = reusable;
        }
        return previous[b.length] <= maximum ? previous[b.length] : overLimit;
    }

    private static int increment(int value, int limit) {
        return add(value, 1, limit);
    }

    private static int add(int value, int amount, int limit) {
        return value >= limit - amount ? limit : value + amount;
    }
}
