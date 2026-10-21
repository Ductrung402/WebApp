package com.ezam.util;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Python str.casefold semantics for option comparison, not locale-sensitive lowercasing. */
public final class CaseFold {
    private static final Map<Integer, String> TABLE = load();
    private CaseFold() { }
    private static Map<Integer, String> load() {
        try (var stream = CaseFold.class.getResourceAsStream("/unicode-casefold.json")) {
            if (stream == null) throw new IllegalStateException();
            var values = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("entries");
            Map<Integer, String> table = new HashMap<>();
            for (var entry : values.entrySet()) table.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsString());
            return Map.copyOf(table);
        } catch (Exception invalid) { throw new IllegalStateException("Casefold data unavailable."); }
    }
    public static String apply(String text) {
        var result = new StringBuilder();
        text.codePoints().forEach(cp -> result.append(TABLE.getOrDefault(cp, new String(Character.toChars(cp)))));
        return result.toString();
    }
}
