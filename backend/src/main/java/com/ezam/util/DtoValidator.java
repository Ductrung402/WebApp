package com.ezam.util;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict field-level building blocks; DTOs still define their own business constraints. */
public final class DtoValidator {
    private DtoValidator() { }
    public static JsonObject object(String body, Set<String> allowed) {
        JsonElement parsed;
        try { parsed = StrictJson.parse(body); }
        catch (IllegalArgumentException error) { throw AuthException.invalid("", "json_invalid", "JSON decode error"); }
        return object(parsed, allowed);
    }
    public static JsonObject object(JsonElement value, Set<String> allowed) {
        if (value == null || !value.isJsonObject()) throw AuthException.invalid("", "model_attributes_type", "Input should be an object");
        JsonObject object = value.getAsJsonObject();
        for (String name : object.keySet()) if (!allowed.contains(name)) throw AuthException.invalid("", "extra_forbidden", "Extra inputs are not permitted");
        return object;
    }
    public static JsonElement required(JsonObject object, String key) {
        if (!object.has(key)) throw AuthException.invalid(key, "missing", "Field required");
        return object.get(key);
    }
    public static String string(JsonObject object, String key, boolean optional) {
        JsonElement value = optional && !object.has(key) ? JsonNull.INSTANCE : required(object, key);
        if (optional && value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw AuthException.invalid(key, "string_type", "Input should be a valid string");
        String text = value.getAsString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)) throw AuthException.invalid(key, "string_unicode", "Input should be valid Unicode");
        return text;
    }
    public static String nonEmpty(String text, String key) {
        if (text == null) throw AuthException.invalid(key, "string_type", "Input should be a valid string");
        String stripped = strip(text);
        if (stripped.isEmpty()) throw AuthException.invalid(key, "string_too_short", "String should have at least 1 character");
        return stripped;
    }
    public static String strip(String text) {
        int start = 0, end = text.length();
        while (start < end && space(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start));
        while (end > start && space(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end));
        return text.substring(start, end);
    }
    private static boolean space(int value) { return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == 0x85; }
    public static int positiveInteger(JsonObject object, String key) {
        JsonElement value = required(object, key);
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !value.getAsString().matches("-?(0|[1-9][0-9]*)")) throw new NumberFormatException();
            int result = Integer.parseInt(value.getAsString());
            if (result < 1) throw AuthException.invalid(key, "greater_than_equal", "Input should be greater than or equal to 1");
            return result;
        } catch (NumberFormatException error) { throw AuthException.invalid(key, "int_type", "Input should be a valid integer"); }
    }
    /** Accept exactly one of camelCase or the documented snake_case alias. */
    public static JsonObject aliases(JsonObject input, Map<String, String> aliases) {
        JsonObject result = new JsonObject();
        for (String key : input.keySet()) {
            String name = aliases.getOrDefault(key, key);
            if (result.has(name)) throw AuthException.invalid(name, "extra_forbidden", "Do not supply both field names");
            result.add(name, input.get(key));
        }
        return result;
    }
}
