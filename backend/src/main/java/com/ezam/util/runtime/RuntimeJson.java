package com.ezam.util.runtime;

import com.ezam.util.StrictJson;
import com.google.gson.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;

/** Record shape/type validation BEFORE Gson conversion: no numeric/string/boolean coercion. */
final class RuntimeJson {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Set<String> NULLABLE = Set.of("disconnectedAt", "frozenRemainingSeconds", "idHs", "idKq", "resultState", "diemSo");
    private RuntimeJson() { }

    static String write(Object value) { return GSON.toJson(value); }
    static JsonElement tree(Object value) { return GSON.toJsonTree(value); }
    static <T> T read(String value, Class<T> type) { return read(StrictJson.parse(value), type); }
    static <T> T read(JsonElement value, Class<T> type) {
        try { check(value, type, false); return GSON.fromJson(value, type); }
        catch (RuntimeException invalid) { throw new RuntimeUnavailableException(); }
    }

    private static void check(JsonElement value, Type type, boolean nullable) {
        if (value == null || value.isJsonNull()) { if (!nullable) throw new IllegalArgumentException(); return; }
        if (type instanceof ParameterizedType generic) {
            if (generic.getRawType() == List.class) {
                if (!value.isJsonArray()) throw new IllegalArgumentException();
                for (JsonElement item : value.getAsJsonArray()) check(item, generic.getActualTypeArguments()[0], false);
            } else if (generic.getRawType() == Map.class) {
                if (!value.isJsonObject()) throw new IllegalArgumentException();
                for (var entry : value.getAsJsonObject().entrySet()) check(entry.getValue(), String.class, false);
            } else throw new IllegalArgumentException();
            return;
        }
        Class<?> target = (Class<?>) type;
        if (target == JsonElement.class) return;
        if (target.isRecord()) {
            if (!value.isJsonObject()) throw new IllegalArgumentException();
            JsonObject object = value.getAsJsonObject();
            Set<String> expected = new HashSet<>();
            for (RecordComponent field : target.getRecordComponents()) {
                expected.add(field.getName());
                if (!object.has(field.getName())) throw new IllegalArgumentException();
                check(object.get(field.getName()), field.getGenericType(), !field.getType().isPrimitive() && NULLABLE.contains(field.getName()));
            }
            if (!object.keySet().equals(expected)) throw new IllegalArgumentException();
            return;
        }
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException();
        JsonPrimitive p = value.getAsJsonPrimitive();
        if (target == String.class || target.isEnum()) {
            if (!p.isString()) throw new IllegalArgumentException();
            if (target.isEnum() && Arrays.stream(target.getEnumConstants()).noneMatch(e -> ((Enum<?>) e).name().equals(p.getAsString()))) throw new IllegalArgumentException();
        } else if (target == boolean.class) {
            if (!p.isBoolean()) throw new IllegalArgumentException();
        } else {
            if (!p.isNumber()) throw new IllegalArgumentException();
            BigDecimal n = p.getAsBigDecimal();
            if ((target == long.class || target == Long.class || target == int.class) && !p.toString().matches("-?(0|[1-9][0-9]*)")) throw new IllegalArgumentException();
            if (target == long.class || target == Long.class) n.longValueExact();
            else if (target == int.class) n.intValueExact();
            else if ((target == double.class || target == Double.class) && !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException();
        }
    }
}
