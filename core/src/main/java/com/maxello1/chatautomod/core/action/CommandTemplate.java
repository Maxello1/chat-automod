package com.maxello1.chatautomod.core.action;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    private static final Set<String> ALLOWED = Set.of("player", "uuid", "points", "rule");
    private final String template;

    private CommandTemplate(String template) {
        this.template = template;
    }

    public static CommandTemplate compile(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        String command = value.startsWith("/") ? value.substring(1) : value;
        if (command.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029)) {
            throw new IllegalArgumentException("command must not contain control or line-separator characters");
        }
        Matcher matcher = PLACEHOLDER.matcher(command);
        while (matcher.find()) {
            if (!ALLOWED.contains(matcher.group(1))) {
                throw new IllegalArgumentException("unsupported command placeholder: {" + matcher.group(1) + "}");
            }
        }
        String withoutKnown = matcher.replaceAll("");
        if (withoutKnown.indexOf('{') >= 0 || withoutKnown.indexOf('}') >= 0) {
            throw new IllegalArgumentException("unbalanced or unsupported command placeholder");
        }
        return new CommandTemplate(command);
    }

    public String expand(String player, String uuid, int points, String rule) {
        Map<String, String> values = Map.of(
                "player", player,
                "uuid", uuid,
                "points", Integer.toString(points),
                "rule", rule
        );
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(values.get(matcher.group(1))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public String template() {
        return template;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CommandTemplate that && template.equals(that.template);
    }

    @Override
    public int hashCode() {
        return template.hashCode();
    }

    @Override
    public String toString() {
        return template;
    }
}
