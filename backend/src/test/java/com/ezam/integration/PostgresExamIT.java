package com.ezam.integration;

import com.ezam.util.*;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** CRUD HTTP against the actual WAR, with test-only PostgreSQL failure triggers. */
class PostgresExamIT {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static DBUtil db;
    private static PostgresAuthIT.TestTomcat server;
    private static final Map<String, Object> proof = new LinkedHashMap<>();
    private static final AtomicInteger sequence = new AtomicInteger();
    @BeforeAll static void setup() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        try {
            URI parsed = new URI(url == null ? "" : url);
            if (!"127.0.0.1".equals(parsed.getHost()) || !"/ezam_t21_test".equals(parsed.getPath()) || !"postgresql".equals(parsed.getScheme())) throw new IllegalArgumentException();
            var config = DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", url));
            try (var c = DriverManager.getConnection(config.getJdbcUrl(), config.toConnectionProperties()); var s = c.prepareStatement("SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()"); var r = s.executeQuery()) {
                assertTrue(r.next()); assertEquals("EZam isolated auth test T2.1", r.getString(1));
            }
            db = DBUtil.open(config, 3, 2000); server = new PostgresAuthIT.TestTomcat(true, true); server.start();
            proof.put("container", "Tomcat 9 actual ROOT.war; isolated PostgreSQL marker checked");
        } catch (Exception unavailable) {
            if (server != null) server.close(); if (db != null) db.close();
            throw new IllegalStateException("Use isolated auth/CRUD test runner.");
        }
    }
    @AfterAll static void cleanup() throws Exception {
        try {
            if (server != null) { server.close(); proof.put("tomcatStopped", true); }
            if (db != null) {
                try (var c = db.getConnection(); var q = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'ezam-t21-http'"); var rows = q.executeQuery()) {
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                }
            }
            proof.put("servletPoolClosed", true);
            Files.writeString(Path.of("target/verification/T3_1_exam.json"), JSON.toJson(proof), StandardCharsets.UTF_8);
        } finally { if (db != null) db.close(); }
    }
    private record Reply(int status, JsonElement json) { }
    private record Teacher(String id, String token) { }
    private static Reply call(String method, String path, Object data, String token) throws Exception {
        String text = data == null ? "" : data instanceof String value ? value : JSON.toJson(data);
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.httpPort+path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=UTF-8")
                .method(method, text.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8));
        if (token != null) req.header("Authorization", "Bearer " + token);
        var response = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        if (response.statusCode() == 401) assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(""));
        return new Reply(response.statusCode(), JsonParser.parseString(response.body()));
    }
    private static Teacher teacher() throws Exception {
        String email = UUID.randomUUID()+"@example.com";
        Reply created = call("POST", "/api/auth/register", Map.of("email", email, "mat_khau", "synthetic-password", "ho_ten", "Exam teacher"), null);
        assertEquals(201, created.status);
        Reply login = call("POST", "/api/auth/login", Map.of("email", email, "mat_khau", "synthetic-password"), null); assertEquals(200, login.status);
        return new Teacher(created.json.getAsJsonObject().get("id_gv").getAsString(), login.json.getAsJsonObject().get("access_token").getAsString());
    }
    private static Map<String, Object> question(String content) {
        return Map.of("noiDung", content, "dapAnA", "Water", "dapAnB", "Air", "dapAnC", "Stone", "dapAnD", "Fire", "dapAnDung", "A");
    }
    private static Map<String, Object> payload(String title, String... contents) {
        Map<String, Object> payload = new LinkedHashMap<>(); payload.put("tenDeThi", title); payload.put("khoi", null);
        payload.put("kieuTaiLieu", "TEXT"); payload.put("noiDungTaiLieu", null);
        payload.put("cauHoiList", Arrays.stream(contents).map(PostgresExamIT::question).toList()); return payload;
    }
    private static JsonObject create(Teacher teacher, String title, String... questions) throws Exception {
        Reply response = call("POST", "/api/exams", payload(title, questions), teacher.token); assertEquals(201, response.status); return response.json.getAsJsonObject();
    }
    private static String id(JsonObject exam) { return exam.get("idDeThi").getAsString(); }
    private static JsonObject detail(Teacher teacher, String id) throws Exception {
        var reply = call("GET", "/api/exams/"+id, null, teacher.token); assertEquals(200, reply.status); return reply.json.getAsJsonObject();
    }
    private static int count(String sql, String key) throws Exception {
        try (var c = db.getConnection(); var s = c.prepareStatement(sql)) { s.setString(1, key); try (var r = s.executeQuery()) { assertTrue(r.next()); return r.getInt(1); } }
    }
    @Test void healthIsPublicAndPreservesLegacyInternalAppString() throws Exception {
        var reply = call("GET", "/api/health", null, null); assertEquals(200, reply.status);
        assertEquals(JsonParser.parseString("{\"status\":\"ok\",\"app\":\"Quiz AI & Exam System\"}"), reply.json);
        assertEquals(405, call("POST", "/api/health", Map.of(), null).status); proof.put("healthExactPublicContract", true);
    }
    @Test void createReturnsCamelCaseIdsCountDateNullsAndAllQuestionsCommitted() throws Exception {
        var t = teacher(); var created = create(t, "  New exam  ", "First?", "Second?", "Third?");
        assertEquals(Set.of("idDeThi", "idGv", "tenDeThi", "khoi", "soLuongCauHoi", "kieuTaiLieu", "ngayTao", "noiDungTaiLieu", "cauHoiList"), created.keySet());
        assertEquals(t.id, created.get("idGv").getAsString()); assertEquals("New exam", created.get("tenDeThi").getAsString());
        assertEquals(3, created.get("soLuongCauHoi").getAsInt()); assertTrue(created.get("khoi").isJsonNull()); assertTrue(created.get("noiDungTaiLieu").isJsonNull());
        assertTrue(created.get("ngayTao").getAsString().matches("\\d{4}-\\d{2}-\\d{2}"));
        assertEquals(3, count("SELECT count(*) FROM cau_hoi WHERE id_de_thi = ?", id(created)));
        assertEquals(1, count("SELECT count(*) FROM de_thi WHERE id_de_thi = ?", id(created)));
        for (var q : created.getAsJsonArray("cauHoiList")) {
            assertEquals(id(created), q.getAsJsonObject().get("idDeThi").getAsString()); assertTrue(q.getAsJsonObject().has("dapAnDung"));
        }
        proof.put("manual201AtomicThreeQuestions", true);
    }
    @Test void snakeCaseAliasesAreAcceptedButResponsesStayCamelCase() throws Exception {
        var t = teacher(); String body = "{\"ten_de_thi\":\"Alias\",\"kieu_tai_lieu\":\"FILE\",\"cau_hoi_list\":[{\"noi_dung\":\"Q\",\"dap_an_a\":\"a\",\"dap_an_b\":\"b\",\"dap_an_c\":\"c\",\"dap_an_d\":\"d\",\"dap_an_dung\":\"D\"}]}";
        var response = call("POST", "/api/exams", body, t.token); assertEquals(201, response.status);
        assertFalse(response.json.getAsJsonObject().has("id_de_thi")); assertEquals("FILE", response.json.getAsJsonObject().get("kieuTaiLieu").getAsString());
    }
    @Test void listIsAnOwnerScopedSummaryArrayAndLegacyNullCountDateRemainNull() throws Exception {
        var a = teacher(); var b = teacher(); create(a, "Owned", "Q?"); create(b, "Other", "Q?");
        String legacy = UUID.randomUUID().toString();
        try (var c = db.getConnection(); var s = c.prepareStatement("INSERT INTO de_thi(id_de_thi,id_gv,ten_de_thi,kieu_tai_lieu) VALUES(?,?,?,?)")) {
            s.setString(1, legacy); s.setString(2, a.id); s.setString(3, "Legacy nullable"); s.setString(4, "TEXT"); s.executeUpdate();
        }
        Reply reply = call("GET", "/api/exams", null, a.token); assertEquals(200, reply.status); assertTrue(reply.json.isJsonArray()); assertEquals(2, reply.json.getAsJsonArray().size());
        for (var element : reply.json.getAsJsonArray()) {
            var row = element.getAsJsonObject(); assertEquals(a.id, row.get("idGv").getAsString()); assertFalse(row.has("noiDungTaiLieu")); assertFalse(row.has("cauHoiList"));
            if (row.get("idDeThi").getAsString().equals(legacy)) { assertTrue(row.get("soLuongCauHoi").isJsonNull()); assertTrue(row.get("ngayTao").isJsonNull()); }
        }
        var updated = call("PUT", "/api/exams/"+legacy, payload("Legacy replaced", "Q?"), a.token);
        assertEquals(200, updated.status); assertTrue(updated.json.getAsJsonObject().get("ngayTao").isJsonNull());
        proof.put("listOwnerAliasesNullDates", true);
    }
    @Test void anotherOwnerOrMissingIdIs404ForReadUpdateDelete() throws Exception {
        var owner = teacher(); var other = teacher(); String id = id(create(owner, "Owned", "Q?"));
        for (String target : List.of(id, "missing-id")) {
            assertEquals(404, call("GET", "/api/exams/"+target, null, other.token).status);
            assertEquals(404, call("PUT", "/api/exams/"+target, payload("Other", "Q?"), other.token).status);
            assertEquals(404, call("DELETE", "/api/exams/"+target, null, other.token).status);
        }
        assertEquals("Owned", detail(owner, id).get("tenDeThi").getAsString()); proof.put("owner404NoLeakOrMutation", true);
    }
    @Test void replaceChangesQuestionIdsAndPreservesExamIdentityDateOwner() throws Exception {
        var t = teacher(); var old = create(t, "Before", "Old one", "Old two", "Old three"); String id = id(old);
        Set<String> oldIds = new HashSet<>(); old.getAsJsonArray("cauHoiList").forEach(q -> oldIds.add(q.getAsJsonObject().get("idCauHoi").getAsString()));
        var reply = call("PUT", "/api/exams/"+id, payload("After", "New one", "New two"), t.token); assertEquals(200, reply.status); var updated = reply.json.getAsJsonObject();
        assertEquals(old.get("ngayTao"), updated.get("ngayTao")); assertEquals(old.get("idGv"), updated.get("idGv")); assertEquals(id, id(updated)); assertEquals(2, updated.get("soLuongCauHoi").getAsInt());
        updated.getAsJsonArray("cauHoiList").forEach(q -> assertFalse(oldIds.contains(q.getAsJsonObject().get("idCauHoi").getAsString())));
        assertEquals(2, count("SELECT count(*) FROM cau_hoi WHERE id_de_thi = ?", id)); proof.put("replacePreservesExamIdentityNewQuestions", true);
    }
    @Test void referencedExamReturns409AndRoomQuestionsRemainUntouched() throws Exception {
        var t = teacher(); var exam = create(t, "Referenced", "Q?"); String id = id(exam); var before = detail(t, id);
        try (var c = db.getConnection(); var s = c.prepareStatement("INSERT INTO phong_thi(id_phong_thi,id_gv,id_de_thi,ma_code_phong_thi,ten_lop,thoi_gian_lam_bai,trang_thai_phong) VALUES(?,?,?,?,?,?, 'DANG_CHO')")) {
            s.setString(1, UUID.randomUUID().toString()); s.setString(2, t.id); s.setString(3, id); s.setString(4, String.format("E%05d", sequence.incrementAndGet())); s.setString(5, "6A"); s.setInt(6, 30); s.executeUpdate();
        }
        for (String method : List.of("PUT", "DELETE")) {
            var response = call(method, "/api/exams/"+id, method.equals("PUT") ? payload("Changed", "Changed?") : null, t.token);
            assertEquals(409, response.status); assertEquals("An exam referenced by an exam room cannot be changed or deleted.", response.json.getAsJsonObject().get("detail").getAsString());
        }
        assertEquals(before, detail(t, id)); assertEquals(1, count("SELECT count(*) FROM phong_thi WHERE id_de_thi = ?", id)); proof.put("referenced409PreservesAggregate", true);
    }
    @Test void deleteRemovesAllQuestionsAndReturnsExactDeletedDto() throws Exception {
        var t = teacher(); String id = id(create(t, "Delete", "One", "Two"));
        var deleted = call("DELETE", "/api/exams/"+id, null, t.token); assertEquals(200, deleted.status);
        assertEquals(Set.of("idDeThi", "status"), deleted.json.getAsJsonObject().keySet()); assertEquals("deleted", deleted.json.getAsJsonObject().get("status").getAsString());
        assertEquals(id, deleted.json.getAsJsonObject().get("idDeThi").getAsString()); assertEquals(0, count("SELECT count(*) FROM cau_hoi WHERE id_de_thi = ?", id));
        assertEquals(0, count("SELECT count(*) FROM de_thi WHERE id_de_thi = ?", id)); proof.put("explicitChildFirstDelete", true);
    }
    @ParameterizedTest @ValueSource(strings={"{}", "null", "{", "{\"tenDeThi\":\"T\",\"cauHoiList\":[]}", "{\"tenDeThi\":\"T\",\"cauHoiList\":false}"})
    void invalidManualPayloadIs422BeforeJwtDatabaseLookup(String body) throws Exception {
        // Intentionally no JWT: structural validation must finish before any auth SELECT.
        var response = call("POST", "/api/exams", body, null); assertEquals(422, response.status); assertTrue(response.json.getAsJsonObject().get("detail").isJsonArray());
    }
    @Test void forbiddenQuestionIdsOwnerCountAndDuplicateOptionsDoNotWrite() throws Exception {
        var t = teacher();
        for (String key : List.of("idGv", "soLuongCauHoi", "idDeThi")) {
            var body = payload("Forbidden", "Q?"); body.put(key, "spoof"); assertEquals(422, call("POST", "/api/exams", body, t.token).status);
        }
        var body = payload("Duplicates", "Q?"); var q = new LinkedHashMap<>(question("Q?")); q.put("dapAnA", " Straße "); q.put("dapAnB", "STRASSE"); body.put("cauHoiList", List.of(q));
        assertEquals(422, call("POST", "/api/exams", body, t.token).status);
        q = new LinkedHashMap<>(question("Q?")); q.put("idCauHoi", "spoof"); body.put("cauHoiList", List.of(q)); assertEquals(422, call("POST", "/api/exams", body, t.token).status);
        assertEquals(0, count("SELECT count(*) FROM de_thi WHERE id_gv = ?", t.id));
    }
    @Test void insertFailureOnSecondQuestionRollsBackExamAndFirstQuestion() throws Exception {
        var t = teacher(); var response = call("POST", "/api/exams", payload("Insert rollback", "First inserted", "fail-second-question"), t.token);
        assertEquals(409, response.status); assertEquals(0, count("SELECT count(*) FROM de_thi WHERE id_gv = ?", t.id));
        assertEquals(0, count("SELECT count(*) FROM cau_hoi q JOIN de_thi e ON e.id_de_thi=q.id_de_thi WHERE e.id_gv = ?", t.id)); proof.put("questionNFailureWholeAggregateRollback", true);
    }
    @Test void replaceFailureRestoresMetadataAndOriginalQuestionIds() throws Exception {
        var t = teacher(); String id = id(create(t, "Original", "Old one", "Old two")); JsonObject before = detail(t, id);
        var response = call("PUT", "/api/exams/"+id, payload("Not committed", "New first", "fail-second-question"), t.token);
        assertEquals(409, response.status); assertEquals(before, detail(t, id)); proof.put("replaceFailureRestoresAllRows", true);
    }
    @Test void actualDeferredCommitFailureRollsBackCreateAndUpdate() throws Exception {
        var t = teacher(); var failed = call("POST", "/api/exams", payload("fail-commit", "One", "Two"), t.token);
        assertEquals(503, failed.status); assertEquals(0, count("SELECT count(*) FROM de_thi WHERE id_gv = ?", t.id));
        String id = id(create(t, "Before commit failure", "Old?")); var before = detail(t, id);
        var updated = call("PUT", "/api/exams/"+id, payload("fail-commit", "Changed?"), t.token);
        assertEquals(503, updated.status); assertEquals(before, detail(t, id)); proof.put("actualCommitFailureCreateUpdateRollback", true);
    }
    @Test void errorAfterParentAndChildDeleteRollsBackBoth() throws Exception {
        var t = teacher(); String id = id(create(t, "fail-delete", "One", "Two")); var before = detail(t, id);
        assertEquals(503, call("DELETE", "/api/exams/"+id, null, t.token).status); assertEquals(before, detail(t, id)); proof.put("deleteFailureRestoresParentAndChildren", true);
    }
    @Test void teacherApisRequireJwtAndFutureAiEndpointsDoNotPretendToWork() throws Exception {
        assertEquals(401, call("GET", "/api/exams", null, null).status);
        assertEquals(401, call("POST", "/api/exams", payload("Unauthorized", "Q?"), null).status);
        var t = teacher();
        assertEquals(503, call("POST", "/api/exams/preview-ai", Map.of("tenDeThi", "Preview", "rawText", "Text", "numQuestions", 1), t.token).status);
        assertEquals(503, call("POST", "/api/exams/generate-ai", Map.of("tenDeThi", "Generate", "rawText", "Text", "numQuestions", 1), t.token).status);
        assertEquals(0, count("SELECT count(*) FROM de_thi WHERE id_gv = ?", t.id));
    }
}
