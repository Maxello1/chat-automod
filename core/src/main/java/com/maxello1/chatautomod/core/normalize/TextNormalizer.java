package com.maxello1.chatautomod.core.normalize;

import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.NormalizationFlag;
import com.maxello1.chatautomod.core.model.NormalizedMessage;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class TextNormalizer {
    private final CompiledAutoModConfig.Normalization config;

    public TextNormalizer(CompiledAutoModConfig.Normalization config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public NormalizedMessage normalize(String original) {
        Objects.requireNonNull(original, "original");
        EnumSet<NormalizationFlag> flags = EnumSet.noneOf(NormalizationFlag.class);
        String value = original;
        if (config.unicodeNfkc()) {
            String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
            if (!normalized.equals(value)) flags.add(NormalizationFlag.UNICODE_NORMALIZED);
            value = normalized;
        }
        value = sanitizeAndNormalizeWhitespace(value, flags).toLowerCase(Locale.ROOT);
        String canonical = value;
        value = substitute(value, config.lookalikeSubstitutions(), flags, NormalizationFlag.LOOKALIKE_REPLACED);
        value = substitute(value, config.leetSubstitutions(), flags, NormalizationFlag.LEETSPEAK_REPLACED);
        String deobfuscated = value;
        String linkNormalized = deobfuscated;
        if (config.normalizeLinkObfuscation()) {
            LinkObfuscationNormalizer.Result result = LinkObfuscationNormalizer.normalize(deobfuscated);
            linkNormalized = result.value();
            if (result.changed()) flags.add(NormalizationFlag.LINK_SEPARATOR_REWRITTEN);
        }
        return new NormalizedMessage(original, canonical, deobfuscated, linkNormalized,
                compact(deobfuscated), collapseRuns(deobfuscated), flags);
    }

    private String sanitizeAndNormalizeWhitespace(String value, EnumSet<NormalizationFlag> flags) {
        StringBuilder result = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (isZeroWidth(cp) && config.removeZeroWidthCharacters()) {
                flags.add(NormalizationFlag.ZERO_WIDTH_REMOVED);
                continue;
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.PRIVATE_USE || type == Character.UNASSIGNED) {
                flags.add(NormalizationFlag.CONTROL_REMOVED);
                continue;
            }
            if (pendingSpace) result.append(' ');
            pendingSpace = false;
            result.appendCodePoint(cp);
        }
        return result.toString().strip();
    }

    private static boolean isZeroWidth(int cp) {
        return cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp == 0xFEFF || cp == 0x00AD;
    }

    private static String substitute(String value, Map<Integer, String> substitutions,
            EnumSet<NormalizationFlag> flags, NormalizationFlag flag) {
        StringBuilder result = new StringBuilder(value.length());
        boolean changed = false;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            String replacement = substitutions.get(cp);
            if (replacement == null) result.appendCodePoint(cp);
            else {
                result.append(replacement);
                changed = true;
            }
        }
        if (changed) flags.add(flag);
        return result.toString();
    }

    private static String compact(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);
        return result.toString();
    }

    private static String collapseRuns(String value) {
        StringBuilder result = new StringBuilder(value.length());
        int previous = -1;
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == previous) count++;
            else {
                previous = cp;
                count = 1;
            }
            if (count <= 2) result.appendCodePoint(cp);
        }
        return result.toString();
    }
}
