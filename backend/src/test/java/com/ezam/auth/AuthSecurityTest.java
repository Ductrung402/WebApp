package com.ezam.auth;

import com.ezam.dto.AuthDTO;
import com.ezam.util.*;
import com.google.gson.*;
import io.jsonwebtoken.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real JWT crypto and strict DTO tests. No application environment/database read. */
class AuthSecurityTest {
    private static final String KEY = "synthetic-jwt-test-key-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static JwtUtil jwt() { return JwtUtil.fromEnvironment(Map.of("JWT_SECRET_KEY", KEY), CLOCK); }
    private static String signed(Map<String, Object> claims) {
        return Jwts.builder().setClaims(claims).signWith(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"), SignatureAlgorithm.HS256).compact();
    }
    @Test void tokenContainsOnlyIdentityIssuedAndExpiryWithThirtyMinutesDefault() {
        String token = jwt().createAccessToken("teacher-id");
        JsonObject claims = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(Set.of("sub", "iat", "exp"), claims.keySet());
        assertEquals(NOW.getEpochSecond(), claims.get("iat").getAsLong());
        assertEquals(NOW.plusSeconds(1800).getEpochSecond(), claims.get("exp").getAsLong());
        assertEquals("teacher-id", jwt().teacherId(token));
        assertEquals("teacher-id", jwt().teacherIdFromAuthorization("bearer " + token));
    }
    @Test void customExpiryAndRawUtf8KeyRemainStableAcrossInstances() {
        var settings = Map.of("JWT_SECRET_KEY", " khoa-test-Unicode-" + "\u1ea3".repeat(16), "ACCESS_TOKEN_EXPIRE_MINUTES", "5");
        String token = JwtUtil.fromEnvironment(settings, CLOCK).createAccessToken("id");
        assertEquals("id", JwtUtil.fromEnvironment(settings, Clock.offset(CLOCK, Duration.ofSeconds(299))).teacherId(token));
        assertEquals(401, assertThrows(AuthException.class, () -> JwtUtil.fromEnvironment(settings, Clock.offset(CLOCK, Duration.ofSeconds(300))).teacherId(token)).status());
    }
    @ParameterizedTest @ValueSource(strings = {"", "short", "0", "-1", "not-an-integer", "9223372036854775807"})
    void badConfigurationFailsWithoutEchoingValue(String value) {
        Map<String, String> settings = value.equals("short") ? Map.of("JWT_SECRET_KEY", value)
                : Map.of("JWT_SECRET_KEY", KEY, "ACCESS_TOKEN_EXPIRE_MINUTES", value);
        var error = assertThrows(IllegalArgumentException.class, () -> JwtUtil.fromEnvironment(settings, CLOCK));
        assertEquals("Invalid authentication configuration.", error.getMessage());
        assertNull(error.getCause());
    }
    @Test void missingConfigHasNoRandomFallback() {
        assertThrows(IllegalArgumentException.class, () -> JwtUtil.fromEnvironment(Map.of(), CLOCK));
        assertEquals("JwtUtil[redacted]", jwt().toString());
    }
    @ParameterizedTest @ValueSource(strings = {"", "abc", "Basic abc", "Bearer", "Bearer ", "Bearer not.a.jwt"})
    void missingMalformedAuthorizationIs401(String header) {
        assertEquals(401, assertThrows(AuthException.class, () -> jwt().teacherIdFromAuthorization(header)).status());
    }
    @Test void nullAuthorizationIs401() { assertThrows(AuthException.class, () -> jwt().teacherIdFromAuthorization(null)); }
    @Test void wrongKeyTamperingAndWrongAlgorithmAreRejected() {
        String token = jwt().createAccessToken("id");
        var other = JwtUtil.fromEnvironment(Map.of("JWT_SECRET_KEY", "different-synthetic-key-32bytes-long"), CLOCK);
        assertThrows(AuthException.class, () -> other.teacherId(token));
        String[] parts = token.split("\\.");
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"other\",\"exp\":1999999999}".getBytes(StandardCharsets.UTF_8));
        assertThrows(AuthException.class, () -> jwt().teacherId(String.join(".", parts)));
        String hs512 = Jwts.builder().setSubject("id").setExpiration(Date.from(NOW.plusSeconds(60)))
                .signWith(new SecretKeySpec("x".repeat(64).getBytes(StandardCharsets.UTF_8), "HmacSHA512"), SignatureAlgorithm.HS512).compact();
        assertThrows(AuthException.class, () -> jwt().teacherId(hs512));
        String unsigned = Jwts.builder().setSubject("id").setExpiration(Date.from(NOW.plusSeconds(60))).compact();
        assertThrows(AuthException.class, () -> jwt().teacherId(unsigned));
    }
    static Stream<Map<String, Object>> invalidClaims() {
        long future = NOW.plusSeconds(60).getEpochSecond();
        return Stream.of(Map.of("exp", future), Map.of("sub", "id"), Map.of("sub", " ", "exp", future),
                Map.of("sub", 123, "exp", future), Map.of("sub", "id", "exp", "1999999999"),
                Map.of("sub", "id", "exp", NOW.getEpochSecond()), Map.of("sub", "id", "exp", NOW.minusSeconds(1).getEpochSecond()),
                Map.of("sub", "id", "exp", future, "iat", "text"));
    }
    @ParameterizedTest @MethodSource("invalidClaims") void requiredClaimsAreChecked(Map<String, Object> claims) {
        assertThrows(AuthException.class, () -> jwt().teacherId(signed(claims)));
    }
    @Test void legacyTokenWithoutOptionalIatCanStillBeVerified() {
        assertEquals("id", jwt().teacherId(signed(Map.of("sub", "id", "exp", NOW.plusSeconds(60).getEpochSecond()))));
    }
    @ParameterizedTest @ValueSource(strings = {"short", "1234567", "\u1ea3\u1ea3\u1ea3\u1ea3\u1ea3\u1ea3\u1ea3", "validpass\u0000"})
    void invalidPasswordIs422(String password) {
        assertEquals(422, assertThrows(AuthException.class, () -> new AuthDTO.Login("a@example.com", password)).status());
    }
    @Test void passwordBoundariesUnicodeAndSpacesArePreserved() {
        assertEquals("  abcdef  ", new AuthDTO.Login("a@example.com", "  abcdef  ").mat_khau());
        assertEquals("\u1ea3".repeat(24), new AuthDTO.Login("a@example.com", "\u1ea3".repeat(24)).mat_khau());
        assertThrows(AuthException.class, () -> new AuthDTO.Login("a@example.com", "\u1ea3".repeat(25)));
        assertThrows(AuthException.class, () -> new AuthDTO.Login("a@example.com", "a".repeat(73)));
        assertThrows(AuthException.class, () -> new AuthDTO.Login("a@example.com", "abcdefgh\uD800"));
        assertEquals(8, new AuthDTO.Login("a@example.com", "\uD83D\uDE00".repeat(8)).mat_khau().codePointCount(0, 16));
    }
    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{", "{}", "{\"email\":42,\"mat_khau\":\"abcdefgh\"}",
            "{\"email\":\"a@example.com\",\"mat_khau\":null}", "{\"email\":\"a@example.com\",\"mat_khau\":\"abcdefgh\",\"id_gv\":\"spoof\"}",
            "{\"email\":\"a@example.com\",\"email\":\"b@example.com\",\"mat_khau\":\"abcdefgh\"}"})
    void strictRequestRejectsInvalidJsonTypesAndExtraKeys(String body) {
        var error = assertThrows(AuthException.class, () -> AuthDTO.login(body));
        assertEquals(422, error.status()); assertFalse(JSON.toJson(error.detail()).contains("abcdefgh"));
    }
    @Test void registrationNormalizesTextAndIncludesNullResponseFieldsWithoutHash() {
        var request = AuthDTO.register("{\"email\":\"Teacher@Example.COM\",\"mat_khau\":\"abcdefgh\",\"ho_ten\":\"  An  \"}");
        assertEquals("teacher@example.com", request.email()); assertEquals("An", request.ho_ten()); assertNull(request.truong());
        var response = new AuthDTO.TeacherResponse("id", request.email(), request.ho_ten(), null, null);
        assertEquals(Set.of("id_gv", "email", "ho_ten", "truong", "tinh"), JsonParser.parseString(JSON.toJson(response)).getAsJsonObject().keySet());
        assertFalse(request.toString().contains("abcdefgh"));
        assertEquals("TokenResponse[redacted]", new AuthDTO.TokenResponse("private-token").toString());
        assertThrows(AuthException.class, () -> new AuthDTO.Register("a@example.com", "abcdefgh", " ", null, null));
        assertThrows(AuthException.class, () -> new AuthDTO.Register("a@example.com", "abcdefgh", "An", " ", null));
    }
    static Stream<Arguments> emailCases() throws Exception {
        try (var input = AuthSecurityTest.class.getResourceAsStream("/auth/email-cases.json")) {
            assertNotNull(input);
            JsonArray cases = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("cases");
            List<Arguments> result = new ArrayList<>();
            for (JsonElement element : cases) {
                JsonObject c = element.getAsJsonObject();
                result.add(Arguments.of(c.get("input").getAsString(), c.get("accepted").getAsBoolean(), c.get("normalized").isJsonNull() ? null : c.get("normalized").getAsString()));
            }
            return result.stream();
        }
    }
    @ParameterizedTest(name = "legacy EmailStr case {index}") @MethodSource("emailCases")
    void emailNormalizationMatchesLegacyFixtures(String input, boolean accepted, String normalized) {
        if (accepted) assertEquals(normalized, AuthDTO.normalizeEmail(input));
        else assertThrows(AuthException.class, () -> AuthDTO.normalizeEmail(input));
    }
}
