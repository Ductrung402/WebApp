package com.ezam.util;

import io.jsonwebtoken.*;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Raw UTF-8 HS256 secret, matching python-jose. Signed tokens are not encrypted. */
public final class JwtUtil {
    private final SecretKey key;
    private final long minutes;
    private final Clock clock;

    private JwtUtil(String secret, long minutes, Clock clock) {
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.minutes = minutes; this.clock = Objects.requireNonNull(clock);
    }
    public static JwtUtil fromEnvironment() { return fromEnvironment(System.getenv(), Clock.systemUTC()); }
    public static JwtUtil fromEnvironment(Map<String, String> environment, Clock clock) {
        try {
            String secret = environment.get("JWT_SECRET_KEY");
            if (secret == null || !StandardCharsets.UTF_8.newEncoder().canEncode(secret)
                    || secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalArgumentException();
            String configured = environment.getOrDefault("ACCESS_TOKEN_EXPIRE_MINUTES", "30");
            long minutes = Long.parseLong(configured.strip());
            if (minutes < 1) throw new IllegalArgumentException();
            Math.multiplyExact(minutes, 60_000);
            return new JwtUtil(secret, minutes, clock);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid authentication configuration."); }
    }
    public String createAccessToken(String teacherId) {
        if (teacherId == null || teacherId.isBlank()) throw new IllegalArgumentException("Teacher identity required.");
        Instant now = clock.instant();
        return Jwts.builder().setSubject(teacherId).setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(Math.multiplyExact(minutes, 60))))
                .signWith(key, SignatureAlgorithm.HS256).compact();
    }
    public String teacherId(String token) {
        try {
            if (token == null || token.length() > 16_384) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw new IllegalArgumentException();
            JsonObject header = jsonPart(parts[0]), body = jsonPart(parts[1]);
            if (!header.has("alg") || !header.get("alg").isJsonPrimitive()
                    || !header.get("alg").getAsJsonPrimitive().isString()
                    || !"HS256".equals(header.get("alg").getAsString())) throw new IllegalArgumentException();
            if (!body.has("sub") || !body.get("sub").isJsonPrimitive() || !body.get("sub").getAsJsonPrimitive().isString()
                    || body.get("sub").getAsString().isBlank()) throw new IllegalArgumentException();
            if (!body.has("exp") || !body.get("exp").isJsonPrimitive() || !body.get("exp").getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            if (body.has("iat") && (!body.get("iat").isJsonPrimitive() || !body.get("iat").getAsJsonPrimitive().isNumber())) throw new IllegalArgumentException();
            Jws<Claims> parsed = Jwts.parserBuilder().setSigningKey(key)
                    .setClock(() -> Date.from(clock.instant())).build().parseClaimsJws(token);
            if (parsed.getBody().getExpiration() == null || !parsed.getBody().getExpiration().toInstant().isAfter(clock.instant())) throw new IllegalArgumentException();
            return parsed.getBody().getSubject();
        } catch (RuntimeException invalid) { throw AuthException.credentials(); }
    }
    public String teacherIdFromAuthorization(String header) {
        if (header == null) throw AuthException.credentials();
        String[] parts = header.split(" ", 2);
        if (parts.length != 2 || !"Bearer".equalsIgnoreCase(parts[0]) || parts[1].isBlank()) throw AuthException.credentials();
        return teacherId(parts[1]);
    }
    private static JsonObject jsonPart(String base64) {
        return StrictJson.parse(new String(Base64.getUrlDecoder().decode(base64), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    @Override public String toString() { return "JwtUtil[redacted]"; }
}
