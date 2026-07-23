package com.maxello1.chatautomod.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DefaultFilterPacks {
    private DefaultFilterPacks() {
    }

    public static Map<String, String> files() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("abusive-language", abusiveLanguage());
        result.put("identity-harassment", identityHarassment());
        result.put("racist-slurs", racistSlurs());
        result.put("antisemitism-extremism", antisemitismExtremism());
        return Map.copyOf(result);
    }

    public static String exceptions() {
        return """
                {
                  "schema_version": 1,
                  "id": "exceptions",
                  "global_exceptions": [
                    {
                      "term": "gay rights",
                      "applies_to": ["identity.homophobic-phrases"]
                    }
                  ]
                }
                """;
    }

    private static String abusiveLanguage() {
        return """
                {
                  "schema_version": 1,
                  "id": "abusive-language",
                  "rules": [
                    {
                      "id": "abusive.moderate-language",
                      "category": "ABUSIVE_LANGUAGE",
                      "severity": "MODERATE",
                      "match_mode": "NORMALIZED_PHRASE",
                      "terms": ["dick head", "pussy", "cunt", "suck my dick", "suck mine", "hairy ass"],
                      "patterns": [],
                      "exceptions": [],
                      "points": 2,
                      "actions": ["BLOCK", "NOTIFY_STAFF"]
                    }
                  ]
                }
                """;
    }

    private static String identityHarassment() {
        return """
                {
                  "schema_version": 1,
                  "id": "identity-harassment",
                  "rules": [
                    {
                      "id": "identity.homophobic-slurs",
                      "category": "IDENTITY_HARASSMENT",
                      "severity": "HIGH",
                      "match_mode": "NORMALIZED_WORD",
                      "terms": ["fegit", "fgt", "schwuchtel", "tunte"],
                      "patterns": [],
                      "exceptions": [],
                      "points": 5,
                      "actions": ["BLOCK", "NOTIFY_STAFF"]
                    },
                    {
                      "id": "identity.homophobic-phrases",
                      "category": "IDENTITY_HARASSMENT",
                      "severity": "HIGH",
                      "match_mode": "NORMALIZED_PHRASE",
                      "terms": ["you are gay", "shotgay", "fucking gay", "camper gay", "hate gay", "ucav gay"],
                      "patterns": [],
                      "exceptions": [],
                      "points": 5,
                      "actions": ["BLOCK", "NOTIFY_STAFF"]
                    }
                  ]
                }
                """;
    }

    private static String racistSlurs() {
        return """
                {
                  "schema_version": 1,
                  "id": "racist-slurs",
                  "rules": [
                    {
                      "id": "racism.nword-family",
                      "category": "RACISM",
                      "severity": "SEVERE",
                      "match_mode": "BUILT_IN_PATTERN",
                      "terms": ["nigger", "nigga", "nibba", "ni66a", "ni66er", "ni99a", "ni99er", "nibber", "niggae", "nhigga", "nggas", "ngga", "ngrs", "nyga", "nyyga", "migger", "nill kiggers"],
                      "patterns": ["builtin:nword-family"],
                      "exceptions": [],
                      "points": 20,
                      "actions": [
                        "BLOCK",
                        "NOTIFY_STAFF",
                        {"type": "MUTE", "duration": "permanent", "reason": "Severe racist language"}
                      ]
                    },
                    {
                      "id": "racism.severe-slurs",
                      "category": "RACISM",
                      "severity": "SEVERE",
                      "match_mode": "NORMALIZED_PHRASE",
                      "terms": ["neekeri", "zwartjoekel", "czarnuch", "sand monkey", "beaner", "wetback", "rag head", "boungoules", "ciapak"],
                      "patterns": [],
                      "exceptions": [],
                      "points": 20,
                      "actions": [
                        "BLOCK",
                        "NOTIFY_STAFF",
                        {"type": "MUTE", "duration": "permanent", "reason": "Severe racist language"}
                      ]
                    },
                    {
                      "id": "racism.contextual-language",
                      "category": "RACISM",
                      "severity": "HIGH",
                      "match_mode": "NORMALIZED_WORD",
                      "terms": ["murzynka", "murzynek", "gypsy", "gypsie"],
                      "patterns": [],
                      "exceptions": [],
                      "points": 8,
                      "actions": ["BLOCK", "NOTIFY_STAFF"]
                    }
                  ]
                }
                """;
    }

    private static String antisemitismExtremism() {
        return """
                {
                  "schema_version": 1,
                  "id": "antisemitism-extremism",
                  "rules": [
                    {
                      "id": "extremism.severe-antisemitism",
                      "category": "ANTISEMITISM_EXTREMISM",
                      "severity": "SEVERE",
                      "match_mode": "NORMALIZED_PHRASE",
                      "terms": ["cancer jew", "death to all jew", "drecks jude", "drecks juden", "drecksjude", "drecksjuden", "fucking jew", "fucking jude", "fucking juden", "gas the jew", "hate jew", "hate jews", "hate jude", "j\u00e4vla jude", "jewish shit", "kanker jood", "kankerjood", "sheis jude", "sheiss jude", "scheiss jude", "schei\u00df jude"],
                      "patterns": [],
                      "exceptions": [],
                      "points": 20,
                      "actions": [
                        "BLOCK",
                        "NOTIFY_STAFF",
                        {"type": "MUTE", "duration": "permanent", "reason": "Severe antisemitic language"}
                      ]
                    },
                    {
                      "id": "extremism.nazi-slogans",
                      "category": "ANTISEMITISM_EXTREMISM",
                      "severity": "SEVERE",
                      "match_mode": "NORMALIZED_PHRASE",
                      "terms": ["hail hitler", "heil hitler", "sieg heil"],
                      "patterns": [],
                      "exceptions": [],
                      "points": 20,
                      "actions": [
                        "BLOCK",
                        "NOTIFY_STAFF",
                        {"type": "MUTE", "duration": "permanent", "reason": "Extremist slogan"}
                      ]
                    },
                    {
                      "id": "extremism.contextual-antisemitism",
                      "category": "ANTISEMITISM_EXTREMISM",
                      "severity": "HIGH",
                      "match_mode": "NORMALIZED_PHRASE",
                      "terms": ["alles juden", "der jude ist da", "fuck jude", "fuck juden", "fuck jew", "fuk jew", "fuck you jude"],
                      "patterns": [],
                      "exceptions": [],
                      "points": 10,
                      "actions": ["BLOCK", "NOTIFY_STAFF"]
                    }
                  ]
                }
                """;
    }
}
