package com.ezam.dto;

import com.ezam.model.GiaoVien;
import com.ezam.util.AuthException;
import com.ezam.util.StrictJson;
import com.google.gson.*;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** Explicit snake_case wire contract. No naming policy and no ORM serialization. */
public final class AuthDTO {
    private AuthDTO() { }

    public record Login(String email, String mat_khau) {
        public Login { email = normalizeEmail(email); password(mat_khau); }
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record Register(String email, String mat_khau, String ho_ten, String truong, String tinh) {
        public Register {
            email = normalizeEmail(email); password(mat_khau);
            ho_ten = text(ho_ten, "ho_ten", false);
            truong = text(truong, "truong", true); tinh = text(tinh, "tinh", true);
        }
        @Override public String toString() { return "Register[redacted]"; }
    }
    public record TeacherResponse(String id_gv, String email, String ho_ten, String truong, String tinh) {
        public TeacherResponse {
            // Legacy response schema validates email and strings before registration COMMIT.
            if (id_gv == null || ho_ten == null) throw new IllegalArgumentException("Invalid teacher response.");
            email = validatedEmail(email, false);
        }
        public static TeacherResponse from(GiaoVien teacher) {
            return new TeacherResponse(teacher.getIdGv(), teacher.getEmail(), teacher.getHoTen(), teacher.getTruong(), teacher.getTinh());
        }
    }
    public record TokenResponse(String access_token, String token_type) {
        public TokenResponse(String token) { this(token, "bearer"); }
        @Override public String toString() { return "TokenResponse[redacted]"; }
    }
    public record Profile(String ho_ten, String truong, String tinh) {
        public Profile {
            ho_ten = text(ho_ten, "ho_ten", false);
            truong = nullableText(truong); tinh = nullableText(tinh);
        }
    }
    public record ChangePassword(String mat_khau_cu, String mat_khau_moi) {
        public ChangePassword { password(mat_khau_cu, "mat_khau_cu"); password(mat_khau_moi, "mat_khau_moi"); }
        @Override public String toString() { return "ChangePassword[redacted]"; }
    }
    public record PasswordResponse(String status, String message) {
        public PasswordResponse() { this("ok", "Đã cập nhật mật khẩu."); }
    }
    public static Profile profile(String body) {
        JsonObject object = object(body, Set.of("ho_ten", "truong", "tinh"));
        return new Profile(string(object, "ho_ten", false), string(object, "truong", true), string(object, "tinh", true));
    }
    public static ChangePassword changePassword(String body) {
        JsonObject object = object(body, Set.of("mat_khau_cu", "mat_khau_moi"));
        return new ChangePassword(string(object, "mat_khau_cu", false), string(object, "mat_khau_moi", false));
    }
    private static String nullableText(String value) {
        return value == null || strip(value).isEmpty() ? null : strip(value);
    }

    public static Login login(String body) {
        JsonObject object = object(body, Set.of("email", "mat_khau"));
        return new Login(string(object, "email", false), string(object, "mat_khau", false));
    }
    public static Register register(String body) {
        JsonObject object = object(body, Set.of("email", "mat_khau", "ho_ten", "truong", "tinh"));
        return new Register(string(object, "email", false), string(object, "mat_khau", false),
                string(object, "ho_ten", false), string(object, "truong", true), string(object, "tinh", true));
    }
    private static JsonObject object(String body, Set<String> keys) {
        JsonElement parsed;
        try { parsed = StrictJson.parse(body); }
        catch (IllegalArgumentException invalid) { throw AuthException.invalid("", "json_invalid", "JSON decode error"); }
        if (!parsed.isJsonObject()) throw AuthException.invalid("", "model_attributes_type", "Input should be an object");
        JsonObject object = parsed.getAsJsonObject();
        for (String key : object.keySet()) if (!keys.contains(key)) {
            // Do not echo attacker-controlled keys into error logs/responses.
            throw AuthException.invalid("", "extra_forbidden", "Extra inputs are not permitted");
        }
        return object;
    }
    private static String string(JsonObject object, String key, boolean optional) {
        if (!object.has(key)) {
            if (optional) return null;
            throw AuthException.invalid(key, "missing", "Field required");
        }
        JsonElement value = object.get(key);
        if (optional && value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw AuthException.invalid(key, "string_type", "Input should be a valid string");
        return value.getAsString();
    }
    private static String text(String value, String key, boolean optional) {
        if (value == null && optional) return null;
        if (value == null) throw AuthException.invalid(key, "string_type", "Input should be a valid string");
        String result = strip(value);
        if (result.isEmpty()) throw AuthException.invalid(key, "string_too_short", "String should have at least 1 character");
        if (!validUnicode(result)) throw AuthException.invalid(key, "string_unicode", "Input should be valid Unicode");
        return result;
    }
    private static void password(String password) {
        password(password, "mat_khau");
    }
    private static void password(String password, String key) {
        if (password == null) throw AuthException.invalid(key, "string_type", "Input should be a valid string");
        int length = password.codePointCount(0, password.length());
        if (length < 8) throw AuthException.invalid(key, "string_too_short", "String should have at least 8 characters");
        if (length > 72 || !validUnicode(password) || password.indexOf('\0') >= 0
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw AuthException.invalid(key, "value_error", "Password must not exceed 72 UTF-8 bytes or contain null bytes.");
        // Intentionally no strip() or Unicode normalization on passwords.
    }
    public static String normalizeEmail(String email) { return validatedEmail(email, true).toLowerCase(Locale.ROOT); }

    private static String validatedEmail(String email, boolean input) {
        try {
            if (email == null || !validUnicode(email) || email.indexOf('\r') >= 0 || email.indexOf('\n') >= 0) throw new IllegalArgumentException();
            String value = strip(email);
            // EmailStr also accepts the conventional display-name form.
            int open = value.indexOf('<');
            if (open >= 0 && value.endsWith(">") && value.indexOf('<', open + 1) < 0)
                value = value.substring(open + 1, value.length() - 1);
            value = Normalizer.normalize(value, Normalizer.Form.NFC);
            int at = value.indexOf('@');
            if (at <= 0 || at != value.lastIndexOf('@') || value.getBytes(StandardCharsets.UTF_8).length > 254) throw new IllegalArgumentException();
            String local = value.substring(0, at), domain = value.substring(at + 1);
            if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) throw new IllegalArgumentException();
            for (int cp : local.codePoints().toArray()) {
                if (cp < 128 && !Character.isLetterOrDigit(cp) && "!#$%&'*+-/=?^_`{|}~.".indexOf(cp) < 0) throw new IllegalArgumentException();
                if (cp >= 128 && (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp)
                        || Character.getType(cp) == Character.FORMAT || Character.getType(cp) == Character.PRIVATE_USE)) throw new IllegalArgumentException();
            }
            String ascii = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (!ascii.contains(".") || ascii.length() > 253 || ascii.endsWith(".") || ascii.matches("[0-9.]+")) throw new IllegalArgumentException();
            for (String label : ascii.split("\\.", -1)) if (label.isEmpty() || label.length() > 63) throw new IllegalArgumentException();
            String top = ascii.substring(ascii.lastIndexOf('.') + 1);
            if (top.matches("[0-9]+") || Set.of("localhost", "local", "test", "invalid", "onion").contains(top)) throw new IllegalArgumentException();
            String normalized = local + "@" + IDN.toUnicode(ascii);
            return normalized;
        } catch (IllegalArgumentException invalid) {
            if (input) throw AuthException.invalid("email", "value_error", "value is not a valid email address");
            throw new IllegalArgumentException("Invalid teacher response.");
        }
    }
    private static boolean validUnicode(String value) { return StandardCharsets.UTF_8.newEncoder().canEncode(value); }
    private static String strip(String value) {
        int start = 0, end = value.length();
        while (start < end && whitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && whitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }
    private static boolean whitespace(int cp) { return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85; }
}
