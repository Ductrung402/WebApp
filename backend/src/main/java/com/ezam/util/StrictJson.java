package com.ezam.util;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

/** JSON syntax only: rejects duplicate keys, trailing input, comments and excessive nesting. */
public final class StrictJson {
    private StrictJson() { }

    public static JsonElement parse(String input) {
        try (JsonReader reader = new JsonReader(new StringReader(input))) {
            reader.setLenient(false);
            JsonElement result = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException();
            return result;
        } catch (IOException | RuntimeException invalid) {
            // Parser diagnostics may contain private request/file values.
            throw new IllegalArgumentException("Invalid JSON document.");
        }
    }

    private static JsonElement read(JsonReader r, int depth) throws IOException {
        if (depth > 64) throw new IOException();
        return switch (r.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); r.beginObject();
                while (r.hasNext()) {
                    String key = r.nextName(); if (object.has(key)) throw new IOException();
                    object.add(key, read(r, depth + 1));
                }
                r.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray(); r.beginArray();
                while (r.hasNext()) array.add(read(r, depth + 1));
                r.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(r.nextString());
            case NUMBER -> new JsonPrimitive(new RawNumber(r.nextString()));
            case BOOLEAN -> new JsonPrimitive(r.nextBoolean());
            case NULL -> { r.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException();
        };
    }

    /** Preserve lexical numeric type (1, 1.0 and 1e0 are distinct for strict integer contracts). */
    private static final class RawNumber extends Number {
        private static final long serialVersionUID = 1L;
        private final String value;
        RawNumber(String value) { new BigDecimal(value); this.value = value; }
        @Override public int intValue() { return new BigDecimal(value).intValue(); }
        @Override public long longValue() { return new BigDecimal(value).longValue(); }
        @Override public float floatValue() { return Float.parseFloat(value); }
        @Override public double doubleValue() { return Double.parseDouble(value); }
        @Override public String toString() { return value; }
    }
}
