package com.maxello1.chatautomod.core.detector;

import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.config.FilterMatchMode;
import com.maxello1.chatautomod.core.model.FilteredText;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.PreventedMatch;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.normalize.FilterTextNormalizer;
import com.maxello1.chatautomod.core.normalize.TextNormalizer;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.util.ArrayList;
import java.util.List;

public final class FilterDetector implements MessageDetector {
    @Override
    public RuleCategory category() {
        return RuleCategory.FILTERED_CONTENT;
    }

    @Override
    public List<RuleMatch> detect(MessageContext context, NormalizedMessage message,
            PlayerModerationState state, CompiledAutoModConfig config) {
        return detectDetailed(context, config).matches();
    }

    public DetectionResult detectDetailed(MessageContext context,
            CompiledAutoModConfig config) {
        FilterTextNormalizer filterNormalizer = new FilterTextNormalizer(config.normalization());
        FilteredText primary = filterNormalizer.normalize(context.rawMessage());
        FilteredText punctuation = filterNormalizer
                .normalizeSeparatingPunctuation(context.rawMessage());
        List<FilteredText> filterVariants = primary.equals(punctuation)
                ? List.of(primary) : List.of(primary, punctuation);
        NormalizedMessage legacyMessage = new TextNormalizer(config.normalization())
                .normalize(context.rawMessage());
        FilteredText legacyText = new FilteredText(
                legacyMessage.deobfuscated(), legacyMessage.compact(), List.of());
        List<RuleMatch> matches = new ArrayList<>();
        List<PreventedMatch> prevented = new ArrayList<>();
        for (var filter : config.filters()) {
            if (!filter.common().enabled()) {
                continue;
            }
            List<FilteredText> variants = isLegacyMode(filter.mode())
                    ? List.of(legacyText) : filterVariants;
            String matched = findPotentialMatch(filter, variants);
            if (matched == null) {
                continue;
            }
            String exception = findBlockingException(filter, matched, variants);
            if (exception != null) {
                prevented.add(new PreventedMatch(filter.id(), exception));
                continue;
            }
            matches.add(new RuleMatch(filter.id(), filter.category(), filter.severity(),
                    filter.common().points(),
                    "filtered " + filter.mode().name().toLowerCase(),
                    matched, filter.common().effect()));
        }
        return new DetectionResult(matches, prevented);
    }

    private String findPotentialMatch(CompiledAutoModConfig.CompiledFilter filter,
            List<FilteredText> variants) {
        for (String term : filter.terms()) {
            if (variants.stream().anyMatch(text -> matchesTerm(text, term, filter.mode()))) {
                return term;
            }
        }
        for (String pattern : filter.patterns()) {
            for (FilteredText text : variants) {
                String matched = BuiltInFilterMatchers.find(pattern, text);
                if (matched != null) {
                    return matched;
                }
            }
        }
        return null;
    }

    private boolean matchesTerm(FilteredText text, String term,
            FilterMatchMode mode) {
        return switch (mode) {
            case SUBSTRING -> text.normalized().contains(term);
            case COMPACT -> text.compact().contains(term);
            case WORD, PHRASE ->
                    containsAtBoundary(text.normalized(), term);
            case BUILT_IN_PATTERN -> containsAtBoundary(text.normalized(), term)
                    || matchesCompactTokenWindow(text, term);
            case NORMALIZED_WORD, NORMALIZED_PHRASE ->
                    containsAtBoundary(text.normalized(), term)
                            || matchesCompactTokenWindow(text, term);
            case COMPACT_WORD, COMPACT_PHRASE -> matchesCompactTokenWindow(text, term);
        };
    }

    private boolean matchesCompactTokenWindow(FilteredText text, String term) {
        String target = compact(term);
        if (target.isEmpty()) {
            return false;
        }
        int targetLength = target.codePointCount(0, target.length());
        for (int start = 0; start < text.tokens().size(); start++) {
            StringBuilder joined = new StringBuilder();
            for (int end = start; end < text.tokens().size() && end < start + 32; end++) {
                joined.append(text.tokens().get(end));
                String candidate = joined.toString();
                if (repetitionTolerantEquals(target, candidate)) {
                    return true;
                }
                if (candidate.codePointCount(0, candidate.length()) >= targetLength * 2) {
                    break;
                }
            }
        }
        return false;
    }

    private String findBlockingException(CompiledAutoModConfig.CompiledFilter filter,
            String matched, List<FilteredText> variants) {
        if (filter.exceptions().isEmpty()) {
            return null;
        }
        String firstBlockingException = null;
        boolean matchedVariantFound = false;
        for (FilteredText text : variants) {
            if (!matchesTerm(text, matched, filter.mode())) {
                continue;
            }
            matchedVariantFound = true;
            Coverage coverage = exceptionCoverage(text, matched,
                    filter.exceptions(), filter.mode());
            if (!coverage.fullyCovered()) {
                return null;
            }
            if (firstBlockingException == null) {
                firstBlockingException = coverage.exception();
            }
        }
        return matchedVariantFound ? firstBlockingException : null;
    }

    private Coverage exceptionCoverage(FilteredText text, String matched,
            List<String> exceptions, FilterMatchMode mode) {
        boolean compactView = mode == FilterMatchMode.COMPACT
                || mode == FilterMatchMode.COMPACT_WORD
                || mode == FilterMatchMode.COMPACT_PHRASE;
        boolean usingCompactView = compactView;
        boolean boundary = mode != FilterMatchMode.SUBSTRING && !compactView;
        String haystack = compactView ? text.compact() : text.normalized();
        String needle = compactView ? compact(matched) : matched;
        List<Span> matches = occurrences(haystack, needle, boundary);
        if (matches.isEmpty() && !isLegacyMode(mode)) {
            haystack = text.compact();
            needle = compact(matched);
            boundary = false;
            usingCompactView = true;
            matches = occurrences(haystack, needle, false);
        }
        if (matches.isEmpty()) {
            return new Coverage(false, null);
        }

        List<CoveredSpan> covers = new ArrayList<>();
        for (String exception : exceptions) {
            String exceptionNeedle = usingCompactView ? compact(exception) : exception;
            if (occurrences(exceptionNeedle, needle, boundary).isEmpty()) {
                continue;
            }
            for (Span span : occurrences(haystack, exceptionNeedle, boundary)) {
                covers.add(new CoveredSpan(span.start(), span.end(), exception));
            }
        }
        for (Span match : matches) {
            boolean covered = covers.stream().anyMatch(span ->
                    span.start() <= match.start() && span.end() >= match.end());
            if (!covered) {
                return new Coverage(false, null);
            }
        }
        return covers.isEmpty()
                ? new Coverage(false, null)
                : new Coverage(true, covers.getFirst().exception());
    }

    private List<Span> occurrences(String haystack, String needle, boolean boundary) {
        List<Span> result = new ArrayList<>();
        if (needle.isEmpty()) {
            return result;
        }
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                return result;
            }
            int end = index + needle.length();
            boolean left = !boundary || index == 0
                    || !Character.isLetterOrDigit(haystack.codePointBefore(index));
            boolean right = !boundary || end == haystack.length()
                    || !Character.isLetterOrDigit(haystack.codePointAt(end));
            if (left && right) {
                result.add(new Span(index, end));
            }
            from = index + 1;
        }
    }

    private static String compact(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);
        return result.toString();
    }

    private static boolean repetitionTolerantEquals(String expected, String candidate) {
        int expectedOffset = 0;
        int candidateOffset = 0;
        while (expectedOffset < expected.length() && candidateOffset < candidate.length()) {
            int expectedCodePoint = expected.codePointAt(expectedOffset);
            int candidateCodePoint = candidate.codePointAt(candidateOffset);
            if (expectedCodePoint != candidateCodePoint) {
                return false;
            }
            int expectedCount = 0;
            while (expectedOffset < expected.length()
                    && expected.codePointAt(expectedOffset) == expectedCodePoint) {
                expectedOffset += Character.charCount(expectedCodePoint);
                expectedCount++;
            }
            int candidateCount = 0;
            while (candidateOffset < candidate.length()
                    && candidate.codePointAt(candidateOffset) == candidateCodePoint) {
                candidateOffset += Character.charCount(candidateCodePoint);
                candidateCount++;
            }
            if (candidateCount < expectedCount
                    || candidateCount > Math.max(2, expectedCount)) {
                return false;
            }
        }
        return expectedOffset == expected.length() && candidateOffset == candidate.length();
    }

    private static boolean isLegacyMode(FilterMatchMode mode) {
        return mode == FilterMatchMode.WORD
                || mode == FilterMatchMode.PHRASE
                || mode == FilterMatchMode.SUBSTRING
                || mode == FilterMatchMode.COMPACT;
    }

    private boolean containsAtBoundary(String haystack, String needle) {
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                return false;
            }
            int end = index + needle.length();
            boolean left = index == 0
                    || !Character.isLetterOrDigit(haystack.codePointBefore(index));
            boolean right = end == haystack.length()
                    || !Character.isLetterOrDigit(haystack.codePointAt(end));
            if (left && right) {
                return true;
            }
            from = index + 1;
        }
    }

    public record DetectionResult(List<RuleMatch> matches,
            List<PreventedMatch> preventedMatches) {
        public DetectionResult {
            matches = List.copyOf(matches);
            preventedMatches = List.copyOf(preventedMatches);
        }
    }

    private record Span(int start, int end) {}
    private record CoveredSpan(int start, int end, String exception) {}
    private record Coverage(boolean fullyCovered, String exception) {}
}
