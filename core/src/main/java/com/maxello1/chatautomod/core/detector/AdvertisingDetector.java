package com.maxello1.chatautomod.core.detector;

import com.maxello1.chatautomod.core.action.RuleEffect;
import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.NormalizationFlag;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdvertisingDetector implements MessageDetector {
    private static final Pattern IPV4 = Pattern.compile("(?<![\\p{L}\\p{N}.:])(\\d{1,3}(?:\\.\\d{1,3}){3})(?::(\\d{1,5}))?(?![.:\\p{L}\\p{N}])");
    private static final Pattern DOMAIN = Pattern.compile("(?i)(?<![a-z0-9-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:[a-z]{2,24}|xn--[a-z0-9-]{2,59}))(?![a-z0-9-])");
    private static final Pattern DISCORD = Pattern.compile("(?i)(?<![a-z0-9-])(?:https?://)?(?:www\\.)?(discord\\.gg|discord(?:app)?\\.com/invite)/([a-z0-9-]{2,})(?![a-z0-9-])");
    private static final RuleEffect DIAGNOSTIC = new RuleEffect(false, List.of());
    private static final Set<String> COMMON_TLDS = Set.of("com", "net", "org", "gg", "io", "co", "uk", "us",
            "de", "dev", "app", "me", "tv", "info", "biz", "xyz", "online", "site", "store", "fr", "nl",
            "ru", "ca", "au", "edu", "gov", "mil", "cloud", "tech", "games", "network", "website", "pro", "club", "live", "shop", "ai",
            "lol", "top", "world", "fun", "icu", "link", "space", "digital", "agency", "studio", "solutions", "services", "social", "today", "news", "wiki");

    @Override public RuleCategory category() { return RuleCategory.ADVERTISING; }

    @Override
    public List<RuleMatch> detect(MessageContext context, NormalizedMessage message, PlayerModerationState state,
            CompiledAutoModConfig config) {
        var rule = config.rules().advertising();
        if (!rule.common().enabled()) return List.of();
        String value = message.linkNormalized();
        List<RuleMatch> result = new ArrayList<>();
        Matcher discord = DISCORD.matcher(value);
        if (discord.find()) {
            result.add(match("advertising.discord_invite", discord.group(), rule));
            addObfuscationDiagnostic(message, result, discord.group());
            return result;
        }
        String numericValue = message.canonical().replaceAll("(?<=\\d)\\s*\\.\\s*(?=\\d)", ".").replaceAll("\\s*:\\s*", ":");
        Matcher ip = IPV4.matcher(numericValue);
        while (ip.find()) {
            if (validIpv4(ip.group(1)) && validPort(ip.group(2))) {
                result.add(match("advertising.ip", ip.group(), rule));
                addObfuscationDiagnostic(message, result, ip.group());
                return result;
            }
        }
        Matcher domain = DOMAIN.matcher(value);
        while (domain.find()) {
            String host = canonicalDomain(domain.group(1));
            if (host != null && (AdvertisingEvidence.recognizedTld(host, COMMON_TLDS) || AdvertisingEvidence.strongUrlEvidence(value, domain, host)) && !allowed(host, rule.allowedDomains())) {
                result.add(match("advertising.domain", host, rule));
                addObfuscationDiagnostic(message, result, host);
                return result;
            }
        }
        return result;
    }

    private RuleMatch match(String id, String value, CompiledAutoModConfig.Advertising rule) {
        return new RuleMatch(id, category(), rule.common().points(), "advertising candidate", value, rule.common().effect());
    }

    private void addObfuscationDiagnostic(NormalizedMessage message, List<RuleMatch> result, String value) {
        if (message.flags().contains(NormalizationFlag.LINK_SEPARATOR_REWRITTEN)) {
            result.add(new RuleMatch("advertising.domain_obfuscation", category(), 0,
                    "obfuscated link separator", value, DIAGNOSTIC));
        }
    }

    private boolean validIpv4(String value) {
        String[] octets = value.split("\\.");
        if (octets.length != 4) return false;
        for (String octet : octets) {
            try {
                int parsed = Integer.parseInt(octet);
                if (parsed < 0 || parsed > 255) return false;
            } catch (NumberFormatException exception) { return false; }
        }
        return true;
    }

    private boolean validPort(String value) {
        if (value == null) return true;
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException exception) { return false; }
    }

    private String canonicalDomain(String value) {
        try { return IDN.toASCII(value.toLowerCase(Locale.ROOT), IDN.USE_STD3_ASCII_RULES); }
        catch (IllegalArgumentException exception) { return null; }
    }

    private boolean allowed(String host, java.util.Set<String> allowedDomains) {
        return allowedDomains.stream().anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
    }
}
