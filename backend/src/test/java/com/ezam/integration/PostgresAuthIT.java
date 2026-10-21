package com.ezam.integration;

import com.ezam.util.*;
import com.google.gson.*;
import io.jsonwebtoken.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual ROOT.war -> Tomcat 9 -> HTTP -> isolated PostgreSQL; no mocked API. */
class PostgresAuthIT {
    private static final String KEY = "synthetic-jwt-test-key-at-least-32-bytes";
    private static final String PASSWORD = "synthetic-password-123";
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static DBUtil database;
    private static TestTomcat server;
    private static final Map<String, Object> proof = new LinkedHashMap<>();

    @BeforeAll static void setup() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        try {
            URI uri = new URI(url == null ? "" : url);
            if (!"postgresql".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || !"/ezam_t21_test".equals(uri.getPath())) throw new IllegalArgumentException();
            var config = DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", url));
            try (var c = DriverManager.getConnection(config.getJdbcUrl(), config.toConnectionProperties());
                 var statement = c.prepareStatement("SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()");
                 var rows = statement.executeQuery()) {
                assertTrue(rows.next()); assertEquals("EZam isolated auth test T2.1", rows.getString(1));
            }
            database = DBUtil.open(config, 3, 2000);
            server = new TestTomcat(true, true); server.start();
            proof.put("httpContainer", "Apache Tomcat 9.0.123 / actual ROOT.war");
            proof.put("database", "disposable loopback PostgreSQL cluster, marker checked");
            proof.put("applicationDatabaseAccessed", false);
        } catch (Exception failure) {
            if (server != null) server.close();
            if (database != null) database.close();
            throw new IllegalStateException("Use the isolated auth runner; inspect its local Tomcat log on startup failure.");
        }
    }
    @AfterAll static void cleanup() throws Exception {
        try {
            if (server != null) { server.close(); proof.put("tomcatStopped", true); }
            if (database != null) {
                try (var c = database.getConnection(); var q = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'ezam-t21-http'"); var rows = q.executeQuery()) {
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1), "Servlet destroy must close its Hikari pool.");
                }
                proof.put("servletPoolClosedOnShutdown", true);
            }
            Files.createDirectories(Path.of("target/verification"));
            Files.writeString(Path.of("target/verification/T2_1_http.json"), JSON.toJson(proof), StandardCharsets.UTF_8);
        } finally { if (database != null) database.close(); }
    }
    private record Reply(int status, JsonObject body, HttpHeaders headers) { }
    private static Reply request(TestTomcat host, String method, String path, Object body, String authorization) throws Exception {
        String encoded = body == null ? "" : body instanceof String text ? text : JSON.toJson(body);
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.httpPort + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=UTF-8")
                .method(method, encoded.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8));
        if (authorization != null) builder.header("Authorization", authorization);
        var response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        return new Reply(response.statusCode(), JsonParser.parseString(response.body()).getAsJsonObject(), response.headers());
    }
    private static Reply request(String method, String path, Object body, String auth) throws Exception { return request(server, method, path, body, auth); }
    private static Map<String, Object> registration(String email) {
        return Map.of("email", email, "mat_khau", PASSWORD, "ho_ten", "  Nguyễn An  ", "truong", "  THPT EZam  ", "tinh", "  Đà Nẵng  ");
    }
    private static String email() { return UUID.randomUUID() + "@example.com"; }
    private static String register(String email) throws Exception {
        Reply reply = request("POST", "/api/auth/register", registration(email), null);
        assertEquals(201, reply.status); return reply.body.get("id_gv").getAsString();
    }
    private static String login(String email, String password) throws Exception {
        Reply reply = request("POST", "/api/auth/login", Map.of("email", email, "mat_khau", password), null);
        assertEquals(200, reply.status); assertEquals(Set.of("access_token", "token_type"), reply.body.keySet());
        assertEquals("bearer", reply.body.get("token_type").getAsString());
        return reply.body.get("access_token").getAsString();
    }
    private static void unauthorized(Reply reply, String detail) {
        assertEquals(401, reply.status); assertEquals(detail, reply.body.get("detail").getAsString());
        assertEquals("Bearer", reply.headers.firstValue("WWW-Authenticate").orElse(""));
    }
    private static String hash(String id) throws Exception {
        try (var c = database.getConnection(); var s = c.prepareStatement("SELECT mat_khau FROM giao_vien WHERE id_gv = ?")) {
            s.setString(1, id); try (var rows = s.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }
    private static int countEmail(String email) throws Exception {
        try (var c = database.getConnection(); var s = c.prepareStatement("SELECT count(*) FROM giao_vien WHERE lower(email) = ?")) {
            s.setString(1, email.toLowerCase(Locale.ROOT)); try (var rows = s.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
        }
    }
    @Test void register201NormalizesAndStoresOnlyHashAndNoToken() throws Exception {
        String email = "Teacher" + UUID.randomUUID() + "@Example.COM";
        Reply reply = request("POST", "/api/auth/register", registration(email), null);
        assertEquals(201, reply.status); assertEquals(Set.of("id_gv", "email", "ho_ten", "truong", "tinh"), reply.body.keySet());
        assertEquals(email.toLowerCase(Locale.ROOT), reply.body.get("email").getAsString());
        assertEquals("Nguyễn An", reply.body.get("ho_ten").getAsString());
        assertEquals("THPT EZam", reply.body.get("truong").getAsString()); assertEquals("Đà Nẵng", reply.body.get("tinh").getAsString());
        String stored = hash(reply.body.get("id_gv").getAsString());
        assertTrue(stored.startsWith("$2a$12$")); assertTrue(PasswordUtil.verifyPassword(PASSWORD, stored));
        assertFalse(reply.body.toString().contains("mat_khau")); assertFalse(reply.body.toString().contains(PASSWORD));
        proof.put("register201NoHashOrToken", true);
    }
    @Test void optionalFieldsAreExplicitNull() throws Exception {
        Reply reply = request("POST", "/api/auth/register", Map.of("email", email(), "mat_khau", PASSWORD, "ho_ten", "An"), null);
        assertEquals(201, reply.status); assertTrue(reply.body.get("truong").isJsonNull()); assertTrue(reply.body.get("tinh").isJsonNull());
    }
    @Test void duplicateEmail409CaseInsensitive() throws Exception {
        String email = email(); register(email);
        Reply reply = request("POST", "/api/auth/register", registration(email.toUpperCase(Locale.ROOT)), null);
        assertEquals(409, reply.status); assertEquals("Email already registered.", reply.body.get("detail").getAsString()); assertEquals(1, countEmail(email));
    }
    @Test void concurrentRegisterHasOneWinnerAndOneConflict() throws Exception {
        String email = email(); ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> request("POST", "/api/auth/register", registration(email), null));
            var b = executor.submit(() -> request("POST", "/api/auth/register", registration(email), null));
            var statuses = new ArrayList<>(List.of(a.get(20, TimeUnit.SECONDS).status, b.get(20, TimeUnit.SECONDS).status));
            Collections.sort(statuses); assertEquals(List.of(201, 409), statuses); assertEquals(1, countEmail(email));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        proof.put("concurrentDuplicate409", true);
    }
    @Test void loginAndMeUseJwtIdentityAndSafeSnakeCaseResponse() throws Exception {
        String email = email(), id = register(email); String token = login(email.toUpperCase(Locale.ROOT), PASSWORD);
        Reply reply = request("GET", "/api/auth/me", null, "Bearer " + token);
        assertEquals(200, reply.status); assertEquals(id, reply.body.get("id_gv").getAsString());
        assertEquals(Set.of("id_gv", "email", "ho_ten", "truong", "tinh"), reply.body.keySet());
        proof.put("loginMeJwtIdentity", true);
    }
    @Test void wrongAndUnknownLoginHaveSame401DetailAndChallenge() throws Exception {
        String email = email(); register(email);
        unauthorized(request("POST", "/api/auth/login", Map.of("email", email, "mat_khau", "wrong-password"), null), "Incorrect email or password.");
        unauthorized(request("POST", "/api/auth/login", Map.of("email", email(), "mat_khau", PASSWORD), null), "Incorrect email or password.");
    }
    @Test void passwordIsNotTrimmedAcrossRegistrationAndLogin() throws Exception {
        String email = email(); var data = new LinkedHashMap<>(registration(email)); data.put("mat_khau", "  abcdefgh  ");
        assertEquals(201, request("POST", "/api/auth/register", data, null).status);
        login(email, "  abcdefgh  ");
        unauthorized(request("POST", "/api/auth/login", Map.of("email", email, "mat_khau", "abcdefgh"), null), "Incorrect email or password.");
    }
    @ParameterizedTest @ValueSource(ints = {0, 1})
    void actualPasslib2bHashesAndMixedCaseLegacyEmailStillLoginWithoutRewrite(int index) throws Exception {
        JsonObject vector;
        try (var input = PostgresAuthIT.class.getResourceAsStream("/password/passlib-bcrypt-vectors.json")) {
            assertNotNull(input); vector = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("vectors").get(index).getAsJsonObject();
        }
        String id = UUID.randomUUID().toString(), email = "Legacy" + UUID.randomUUID() + "@Example.COM";
        String oldHash = vector.get("hash").getAsString();
        try (var c = database.getConnection(); var s = c.prepareStatement("INSERT INTO giao_vien(id_gv,email,mat_khau,ho_ten) VALUES(?,?,?,?)")) {
            s.setString(1, id); s.setString(2, email); s.setString(3, oldHash); s.setString(4, "Legacy teacher"); assertEquals(1, s.executeUpdate());
        }
        String token = login(email.toLowerCase(Locale.ROOT), vector.get("password").getAsString());
        assertEquals(200, request("GET", "/api/auth/me", null, "Bearer " + token).status);
        assertTrue(oldHash.equals(hash(id)), "Stored Passlib hash must be unchanged.");
        assertEquals(409, request("POST", "/api/auth/register", registration(email.toLowerCase(Locale.ROOT)), null).status);
        proof.put("actualPasslib2bLoginNoRewrite", true);
    }
    @Test void missingMalformedExpiredAndWrongSignatureJwtAre401() throws Exception {
        unauthorized(request("GET", "/api/auth/me", null, null), "Invalid or expired credentials.");
        unauthorized(request("GET", "/api/auth/me", null, "Bearer not.a.jwt"), "Invalid or expired credentials.");
        String id = register(email());
        String expired = Jwts.builder().setSubject(id).setExpiration(new java.util.Date(System.currentTimeMillis() - 10_000))
                .signWith(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"), SignatureAlgorithm.HS256).compact();
        unauthorized(request("GET", "/api/auth/me", null, "Bearer " + expired), "Invalid or expired credentials.");
        String foreign = JwtUtil.fromEnvironment(Map.of("JWT_SECRET_KEY", "different-synthetic-key-32bytes-long"), java.time.Clock.systemUTC()).createAccessToken(id);
        unauthorized(request("GET", "/api/auth/me", null, "Bearer " + foreign), "Invalid or expired credentials.");
        proof.put("missingInvalidExpiredJwt401WithChallenge", true);
    }
    @Test void deletedTeacherIs401EvenWithValidSignedToken() throws Exception {
        // Fresh independent teacher, then delete its row; token alone does not establish membership.
        String email = email(), id = register(email), token = login(email, PASSWORD);
        try (var c = database.getConnection(); var s = c.prepareStatement("DELETE FROM giao_vien WHERE id_gv = ?")) { s.setString(1, id); assertEquals(1, s.executeUpdate()); }
        unauthorized(request("GET", "/api/auth/me", null, "Bearer " + token), "Invalid or expired credentials.");
    }
    @ParameterizedTest @ValueSource(strings = {"{", "null", "[]", "{}", "{\"email\":1,\"mat_khau\":\"abcdefgh\",\"ho_ten\":\"An\"}",
            "{\"email\":\"wrong\",\"mat_khau\":\"abcdefgh\",\"ho_ten\":\"An\"}",
            "{\"email\":\"not-written@example.com\",\"mat_khau\":\"short\",\"ho_ten\":\"An\"}",
            "{\"email\":\"not-written@example.com\",\"mat_khau\":\"abcdefgh\",\"ho_ten\":\"An\",\"id_gv\":\"spoof\"}",
            "{\"email\":\"not-written@example.com\",\"mat_khau\":\"abcdefgh\",\"ho_ten\":\"An\",\"truong\":\" \"}"})
    void invalidRegistrationIs422AndNeverWritesRow(String body) throws Exception {
        Reply reply = request("POST", "/api/auth/register", body, null);
        assertEquals(422, reply.status); assertTrue(reply.body.get("detail").isJsonArray());
        assertEquals(0, countEmail("not-written@example.com"));
        assertFalse(reply.body.toString().contains("abcdefgh"));
    }
    @Test void actualDeferredCommitFailureRollsBackAndReturnsSafe503() throws Exception {
        Reply reply = request("POST", "/api/auth/register", registration("commit-failure@example.com"), null);
        assertEquals(503, reply.status); assertEquals("Unable to register teacher.", reply.body.get("detail").getAsString());
        assertEquals(0, countEmail("commit-failure@example.com"));
        // Pool/transaction remains usable after the failed PostgreSQL COMMIT.
        register(email()); proof.put("actualCommitFailureRollbackZeroRows", true);
    }
    @Test void unknownRoutesAndMethodsAreJsonAndFutureTasksAreNotPretendedImplemented() throws Exception {
        assertEquals(404, request("PUT", "/api/auth/unknown-operation", Map.of(), null).status);
        assertEquals(405, request("GET", "/api/auth/register", null, null).status);
    }
    @Test void profileIsPersistedAndMeImmediatelyReturnsNewInfoWithoutChangingIdentityOrHash() throws Exception {
        String email = email(), id = register(email), originalHash = hash(id), token = login(email, PASSWORD);
        Reply response = request("PUT", "/api/auth/profile", Map.of("ho_ten", "  Hồ sơ mới  ", "truong", "  THCS B  ", "tinh", "  Hà Nội  "), "Bearer " + token);
        assertEquals(200, response.status); assertEquals(id, response.body.get("id_gv").getAsString());
        assertEquals("Hồ sơ mới", response.body.get("ho_ten").getAsString()); assertEquals("THCS B", response.body.get("truong").getAsString());
        assertEquals(email, response.body.get("email").getAsString()); assertTrue(originalHash.equals(hash(id)));
        assertEquals(response.body, request("GET", "/api/auth/me", null, "Bearer " + token).body);
        Reply cleared = request("PUT", "/api/auth/profile", Map.of("ho_ten", "An", "truong", "  "), "Bearer " + token);
        assertEquals(200, cleared.status); assertTrue(cleared.body.get("truong").isJsonNull()); assertTrue(cleared.body.get("tinh").isJsonNull());
        proof.put("profileCommittedAndMeUpdated", true);
    }
    @Test void wrongOldPasswordThenSuccessfulChangeAndReloginWithExistingTokenStillValid() throws Exception {
        String email = email(), id = register(email), token = login(email, PASSWORD), oldHash = hash(id), next = "  NewSecret456!  ";
        Reply wrong = request("PUT", "/api/auth/change-password", Map.of("mat_khau_cu", "wrong-password", "mat_khau_moi", next), "Bearer " + token);
        assertEquals(400, wrong.status); assertEquals("Mật khẩu hiện tại không chính xác.", wrong.body.get("detail").getAsString());
        assertTrue(oldHash.equals(hash(id)));
        Reply changed = request("PUT", "/api/auth/change-password", Map.of("mat_khau_cu", PASSWORD, "mat_khau_moi", next), "Bearer " + token);
        assertEquals(200, changed.status); assertEquals(Set.of("status", "message"), changed.body.keySet());
        assertEquals("Đã cập nhật mật khẩu.", changed.body.get("message").getAsString());
        assertTrue(PasswordUtil.verifyPassword(next, hash(id))); assertFalse(PasswordUtil.verifyPassword(PASSWORD, hash(id)));
        assertEquals(200, request("GET", "/api/auth/me", null, "Bearer " + token).status);
        unauthorized(request("GET", "/api/auth/me", null, null), "Invalid or expired credentials.");
        unauthorized(request("POST", "/api/auth/login", Map.of("email", email, "mat_khau", PASSWORD), null), "Incorrect email or password.");
        login(email, next); proof.put("passwordChangedReloginOldJwtExpiresNormally", true);
    }
    @Test void simultaneousPasswordChangesVerifyLatestLockedHash() throws Exception {
        String email = email(); register(email); String token = login(email, PASSWORD);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> request("PUT", "/api/auth/change-password", Map.of("mat_khau_cu", PASSWORD, "mat_khau_moi", "first-new-password"), "Bearer " + token));
            var b = executor.submit(() -> request("PUT", "/api/auth/change-password", Map.of("mat_khau_cu", PASSWORD, "mat_khau_moi", "second-new-password"), "Bearer " + token));
            var statuses = new ArrayList<>(List.of(a.get(20, TimeUnit.SECONDS).status, b.get(20, TimeUnit.SECONDS).status));
            Collections.sort(statuses); assertEquals(List.of(200, 400), statuses);
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        proof.put("passwordConcurrentLatestHashLock", true);
    }
    @Test void profileAndPasswordActualCommitFailuresPreservePreviousData() throws Exception {
        String email = email(), id = register(email), token = login(email, PASSWORD), original = hash(id);
        JsonObject before = request("GET", "/api/auth/me", null, "Bearer " + token).body;
        Reply profile = request("PUT", "/api/auth/profile", Map.of("ho_ten", "commit-profile-failure", "truong", "changed"), "Bearer " + token);
        assertEquals(503, profile.status); assertEquals("Unable to update teacher profile.", profile.body.get("detail").getAsString());
        assertEquals(before, request("GET", "/api/auth/me", null, "Bearer " + token).body); assertTrue(original.equals(hash(id)));
        String passwordId = register("password-failure@example.com"), passwordToken = login("password-failure@example.com", PASSWORD), old = hash(passwordId);
        Reply password = request("PUT", "/api/auth/change-password", Map.of("mat_khau_cu", PASSWORD, "mat_khau_moi", "replacement-password"), "Bearer " + passwordToken);
        assertEquals(503, password.status); assertEquals("Unable to change teacher password.", password.body.get("detail").getAsString());
        assertTrue(old.equals(hash(passwordId))); login("password-failure@example.com", PASSWORD);
        proof.put("profilePasswordActualCommitRollback", true);
    }
    @Test void profileAndPasswordValidationAndIdentityCannotBeSpoofed() throws Exception {
        String email = email(); register(email); String token = login(email, PASSWORD);
        for (Object body : List.of(Map.of("ho_ten", " "), Map.of("ho_ten", "An", "id_gv", "other"), Map.of("ho_ten", "An", "email", "other@example.com"), Map.of("ho_ten", "An", "truong", 42)))
            assertEquals(422, request("PUT", "/api/auth/profile", body, "Bearer " + token).status);
        assertEquals(422, request("PUT", "/api/auth/change-password", Map.of("mat_khau_cu", PASSWORD, "mat_khau_moi", "short"), "Bearer " + token).status);
        unauthorized(request("PUT", "/api/auth/profile", Map.of("ho_ten", "An"), null), "Invalid or expired credentials.");
        unauthorized(request("PUT", "/api/auth/change-password", Map.of("mat_khau_cu", PASSWORD, "mat_khau_moi", "new-password"), null), "Invalid or expired credentials.");
    }
    @Test void missingLockedAccountReturns404AndOutputValidationFailureRollsBack() throws Exception {
        var service = new com.ezam.service.AuthService(new com.ezam.service.TransactionManager(database::getConnection), new com.ezam.dao.TeacherDAO(), JwtUtil.fromEnvironment(Map.of("JWT_SECRET_KEY", KEY), java.time.Clock.systemUTC()));
        assertEquals(404, assertThrows(AuthException.class, () -> service.updateTeacherProfile("missing", new com.ezam.dto.AuthDTO.Profile("An", null, null))).status());
        assertEquals(404, assertThrows(AuthException.class, () -> service.changeTeacherPassword("missing", new com.ezam.dto.AuthDTO.ChangePassword(PASSWORD, "new-password"))).status());
        String id = UUID.randomUUID().toString();
        try (var c = database.getConnection(); var s = c.prepareStatement("INSERT INTO giao_vien(id_gv,email,mat_khau,ho_ten) VALUES(?,?,?,?)")) {
            s.setString(1, id); s.setString(2, "invalid-email-"+id); s.setString(3, "storage-fixture-only"); s.setString(4, "Before"); s.executeUpdate();
        }
        assertThrows(IllegalArgumentException.class, () -> service.updateTeacherProfile(id, new com.ezam.dto.AuthDTO.Profile("After", null, null)));
        try (var c = database.getConnection(); var s = c.prepareStatement("SELECT ho_ten FROM giao_vien WHERE id_gv = ?")) {
            s.setString(1, id); try (var rows = s.executeQuery()) { assertTrue(rows.next()); assertEquals("Before", rows.getString(1)); }
        }
        proof.put("profileOutputValidationBeforeCommit", true);
    }
    @Test void orderedCorsFilterHandlesRealPreflightBeforeMissingJwt() throws Exception {
        var uri = URI.create("http://127.0.0.1:" + server.httpPort + "/api/auth/profile");
        var response = CLIENT.send(HttpRequest.newBuilder(uri).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "authorization,content-type").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); assertEquals("http://localhost:5173", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertFalse(response.headers().firstValue("WWW-Authenticate").isPresent());
        var denied = CLIENT.send(HttpRequest.newBuilder(uri).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://attacker.example").header("Access-Control-Request-Method", "PUT").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, denied.statusCode()); assertFalse(denied.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        proof.put("realOrderedCorsPreflight", true);
    }
    @Test void protectedFutureRoutesAndEncodedAliasesCannotBypassAuthentication() throws Exception {
        for (String path : List.of("/api/exams", "/api/%65xams", "/api/rooms/test-id", "/api/auth/profile")) {
            var response = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.httpPort+path)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode(), path); assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(""));
        }
        proof.put("encodedProtectedPaths401", true);
    }
    @Test void javaAndPythonJoseTokensInteroperateBothDirections() throws Exception {
        String email = email(), id = register(email), token = login(email, PASSWORD);
        JsonObject verified = JsonParser.parseString(python(Map.of("operation", "verify", "token", token))).getAsJsonObject();
        assertEquals(id, verified.get("sub").getAsString()); assertEquals(1800, verified.get("lifetime").getAsInt());
        String legacyToken = python(Map.of("operation", "issue", "id", id)).strip();
        Reply reply = request("GET", "/api/auth/me", null, "Bearer " + legacyToken);
        assertEquals(200, reply.status); assertEquals(id, reply.body.get("id_gv").getAsString());
        proof.put("pythonJoseJavaJwtBothDirections", true);
    }
    private static String python(Object input) throws Exception {
        String executable = System.getenv("EZAM_TEST_PYTHON"); assertNotNull(executable);
        Process child = new ProcessBuilder(executable, "-B", "src/test/fixtures/jwt_interop.py").start();
        try {
            try (var stdin = child.getOutputStream()) { stdin.write(JSON.toJson(input).getBytes(StandardCharsets.UTF_8)); }
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(child.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, child.exitValue(), "Synthetic JWT interoperability helper failed.");
            return output;
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }
    @Test void missingSecretAndMissingDatabaseConfigurationFailSafelyOverHttp() throws Exception {
        try (var missingJwt = new TestTomcat(false, true)) {
            missingJwt.start(); Reply reply = request(missingJwt, "GET", "/api/auth/me", null, null);
            assertEquals(503, reply.status); assertEquals("Authentication service unavailable.", reply.body.get("detail").getAsString());
        }
        try (var missingDb = new TestTomcat(true, false)) {
            missingDb.start(); assertEquals(503, request(missingDb, "POST", "/api/auth/login", Map.of("email", email(), "mat_khau", PASSWORD), null).status);
        }
        proof.put("missingConfigSafe503ActualHttp", true);
    }

    static final class TestTomcat implements AutoCloseable {
        private final Path home, base;
        final int httpPort;
        private final int shutdownPort;
        private final String shutdown = "test-" + UUID.randomUUID();
        private final boolean jwtConfigured, dbConfigured;
        private final String geminiStub;
        private final Map<String,String> socketEnvironment;
        private Process process;
        TestTomcat(boolean jwtConfigured, boolean dbConfigured) throws Exception {
            this(jwtConfigured, dbConfigured, null);
        }
        TestTomcat(boolean jwtConfigured, boolean dbConfigured, String geminiStub) throws Exception {
            this(jwtConfigured,dbConfigured,geminiStub,Map.of());
        }
        TestTomcat(boolean jwtConfigured, boolean dbConfigured, String geminiStub,Map<String,String> socketEnvironment) throws Exception {
            this.jwtConfigured = jwtConfigured; this.dbConfigured = dbConfigured;
            this.geminiStub = geminiStub;
            this.socketEnvironment=Map.copyOf(socketEnvironment);
            home = Path.of(Objects.requireNonNull(System.getenv("EZAM_TEST_TOMCAT_HOME"))).toAbsolutePath();
            assertTrue(home.getFileName().toString().startsWith("apache-tomcat-9."), "Only the approved Tomcat 9 namespace.");
            base = Files.createTempDirectory(Path.of("target/verification"), "t21-tomcat-").toAbsolutePath();
            for (String dir : List.of("conf", "logs", "webapps", "work", "temp")) Files.createDirectories(base.resolve(dir));
            for (String file : List.of("web.xml", "context.xml", "catalina.properties", "logging.properties")) Files.copy(home.resolve("conf/" + file), base.resolve("conf/" + file));
            httpPort = port(); shutdownPort = port();
            String xml = "<Server port=\"" + shutdownPort + "\" address=\"127.0.0.1\" shutdown=\"" + shutdown + "\">"
                    + "<Service name=\"Catalina\"><Connector address=\"127.0.0.1\" port=\"" + httpPort + "\" protocol=\"HTTP/1.1\"/>"
                    + "<Engine name=\"Catalina\" defaultHost=\"localhost\"><Host name=\"localhost\" appBase=\"webapps\" autoDeploy=\"false\" unpackWARs=\"true\"/></Engine></Service></Server>";
            Files.writeString(base.resolve("conf/server.xml"), xml, StandardCharsets.UTF_8);
            if (geminiStub == null) Files.copy(Path.of(System.getProperty("ezam.war.path")), base.resolve("webapps/ROOT.war"));
            else {
                // Inject test dependency only; all production classes/libraries remain byte-identical.
                try (var source = new java.util.zip.ZipFile(System.getProperty("ezam.war.path"));
                     var target = new java.util.zip.ZipOutputStream(Files.newOutputStream(base.resolve("webapps/ROOT.war")))) {
                    for (var entry : source.stream().toList()) {
                        target.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                        if (entry.getName().equals("WEB-INF/web.xml")) {
                            String descriptor = new String(source.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                            descriptor = descriptor.replace("</web-app>", "<listener><listener-class>com.ezam.integration.StubGeminiListener</listener-class></listener></web-app>");
                            target.write(descriptor.getBytes(StandardCharsets.UTF_8));
                        } else try (var input = source.getInputStream(entry)) { input.transferTo(target); }
                        target.closeEntry();
                    }
                    String name = "com/ezam/integration/StubGeminiListener.class";
                    target.putNextEntry(new java.util.zip.ZipEntry("WEB-INF/classes/"+name));
                    try (var input = PostgresAuthIT.class.getResourceAsStream("/"+name)) { assertNotNull(input); input.transferTo(target); }
                    target.closeEntry();
                }
            }
        }
        private static int port() throws IOException { try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
        void start() throws Exception {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java" + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
            var command = new ProcessBuilder(java.toString(), "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED",
                    "-Dcatalina.home=" + home, "-Dcatalina.base=" + base, "-Djava.io.tmpdir=" + base.resolve("temp"),
                    "-Djava.util.logging.config.file=" + base.resolve("conf/logging.properties"),
                    "-cp", home.resolve("bin/bootstrap.jar") + File.pathSeparator + home.resolve("bin/tomcat-juli.jar"), "org.apache.catalina.startup.Bootstrap", "start");
            if("true".equals(socketEnvironment.get("EZAM_TEST_LOCAL_LAUNCHER"))) command=new ProcessBuilder("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",Path.of("../scripts/start_backend.ps1").toAbsolutePath().normalize().toString(),"-TomcatHome",home.toString(),"-BaseDirectory",base.toString(),"-HttpPort",Integer.toString(httpPort),"-ShutdownPort",Integer.toString(shutdownPort),"-ShutdownCommand",shutdown,"-NoBuild","-NoEnvFile");
            var env = command.environment();
            for(String key:List.of("SOCKET_PORT","SOCKET_HOST","RUNTIME_DIRECTORY"))env.remove(key);
            env.putAll(socketEnvironment);
            for (String key : List.of("DATABASE_URL", "PGHOST", "PGHOSTADDR", "PGPORT", "PGUSER", "PGPASSWORD", "PGDATABASE", "PGSERVICE", "JWT_SECRET_KEY", "ACCESS_TOKEN_EXPIRE_MINUTES", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "GEMINI_API_KEY", "GOOGLE_API_KEY", "GEMINI_MODEL", "TEST_GEMINI_ENDPOINT")) env.remove(key);
            if (geminiStub != null) env.put("TEST_GEMINI_ENDPOINT", geminiStub);
            if("true".equals(socketEnvironment.get("EZAM_TEST_LIVE_GEMINI"))){env.put("GEMINI_API_KEY",Objects.requireNonNull(System.getenv("EZAM_TEST_GEMINI_KEY")));if(System.getenv("EZAM_TEST_GEMINI_MODEL")!=null)env.put("GEMINI_MODEL",System.getenv("EZAM_TEST_GEMINI_MODEL"));}
            if (dbConfigured) env.put("DATABASE_URL", System.getenv("TEST_DATABASE_URL").replace("ApplicationName=ezam-t21-pool", "ApplicationName=ezam-t21-http"));
            if (jwtConfigured) env.put("JWT_SECRET_KEY", KEY);
            command.redirectErrorStream(true).redirectOutput(base.resolve("tomcat.log").toFile()); process = command.start();
            long until = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < until && process.isAlive()) {
                try {
                    int status = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/api/auth/me")).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                    if (status == (jwtConfigured && dbConfigured ? 401 : 503)) return;
                } catch (IOException notReady) { /* local container startup only */ }
                Thread.sleep(100);
            }
            throw new IllegalStateException("Tomcat auth startup failed; inspect local test log.");
        }
        @Override public void close() throws Exception {
            if (process == null) return;
            try {
                if (process.isAlive()) {
                    try (var socket = new Socket("127.0.0.1", shutdownPort)) { socket.getOutputStream().write(shutdown.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush(); }
                    if (!process.waitFor(20, TimeUnit.SECONDS)) throw new IllegalStateException("Test Tomcat shutdown timeout.");
                    assertEquals(0, process.exitValue());
                }
            } finally {
                if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
                try (var check = new Socket()) {
                    try { check.connect(new InetSocketAddress("127.0.0.1", httpPort), 500); fail("Test HTTP port still open after shutdown."); }
                    catch (ConnectException closed) { /* expected */ }
                }
            }
        }
    }
}
