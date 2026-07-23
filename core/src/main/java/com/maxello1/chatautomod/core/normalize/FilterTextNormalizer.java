package com.maxello1.chatautomod.core.normalize;

import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.FilteredText;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class FilterTextNormalizer {
    private final CompiledAutoModConfig.Normalization config;

    public FilterTextNormalizer(CompiledAutoModConfig.Normalization config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public FilteredText normalize(String input) {
        return normalize(input, false);
    }

    public FilteredText normalizeSeparatingPunctuation(String input) {
        return normalize(input, true);
    }

    private FilteredText normalize(String input, boolean punctuationAsSeparators) {
        Objects.requireNonNull(input, "input");
        String value = config.unicodeNfkc()
                ? Normalizer.normalize(input, Normalizer.Form.NFKC)
                : input;
        value = clean(value).toLowerCase(Locale.ROOT);
        value = substitute(value, config.lookalikeSubstitutions(), false);
        value = substitute(value, config.filterLeetSubstitutions(), punctuationAsSeparators);
        value = value.replace("\u00df", "ss");
        String separated = normalizeSeparators(value);
        String collapsed = collapseRuns(separated);
        List<String> tokens = collapsed.isEmpty()
                ? List.of()
                : List.of(collapsed.split(" "));
        StringBuilder compact = new StringBuilder(collapsed.length());
        collapsed.codePoints().filter(Character::isLetterOrDigit).forEach(compact::appendCodePoint);
        return new FilteredText(collapsed, compact.toString(), tokens);
    }

    private String clean(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (config.removeZeroWidthCharacters() && isZeroWidth(codePoint)) {
                continue;
            }
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.SURROGATE || type == Character.PRIVATE_USE
                    || type == Character.UNASSIGNED) {
                continue;
            }
            result.appendCodePoint(codePoint);
        }
        return result.toString();
    }

    private static String substitute(String value, Map<Integer, String> substitutions,
            boolean preservePunctuation) {
        StringBuilder result = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            String replacement = preservePunctuation && !Character.isLetterOrDigit(codePoint)
                    ? null : substitutions.get(codePoint);
            result.append(replacement == null
                    ? new String(Character.toChars(codePoint)) : replacement);
        }
        return result.toString();
    }

    private static String normalizeSeparators(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                if (pendingSpace && !result.isEmpty()) {
                    result.append(' ');
                }
                pendingSpace = false;
                result.appendCodePoint(codePoint);
            } else {
                pendingSpace = !result.isEmpty();
            }
        }
        return result.toString().strip();
    }

    private static String collapseRuns(String value) {
        StringBuilder result = new StringBuilder(value.length());
        int previous = -1;
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            count = codePoint == previous ? count + 1 : 1;
            previous = codePoint;
            if (codePoint == ' ' || count <= 2) {
                result.appendCodePoint(codePoint);
            }
        }
        return result.toString();
    }

    private static boolean isZeroWidth(int codePoint) {
        return codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
                || codePoint == 0x2060 || codePoint == 0xFEFF || codePoint == 0x00AD;
    }
}
