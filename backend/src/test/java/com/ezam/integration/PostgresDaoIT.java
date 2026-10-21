package com.ezam.integration;

import com.ezam.dao.*;
import com.ezam.model.*;
import com.ezam.service.TransactionManager;
import com.ezam.util.DBUtil;
import com.ezam.util.DatabaseConfig;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.ezam.dao.ExamDAO.MutationResult.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL/constraints only. No Servlet, HTTP mapping, mocked PostgreSQL or application DB. */
class PostgresDaoIT {
    private static DatabaseConfig config;
    private static DBUtil pool;
    private static TransactionManager tx;
    private static final TeacherDAO teachers = new TeacherDAO();
    private static final ExamDAO exams = new ExamDAO();
    private static final RoomDAO rooms = new RoomDAO();
    private static final StudentDAO students = new StudentDAO();
    private static final SubmissionDAO submissions = new SubmissionDAO();
    private static final AtomicInteger codes = new AtomicInteger();
    private static final Map<String, Object> proof = new LinkedHashMap<>();

    @BeforeAll static void isolatedDatabaseOnly() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        try {
            URI uri = new URI(url == null ? "" : url);
            if (!"postgresql".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || !"/ezam_t13_test".equals(uri.getPath()) || uri.getPort() < 1) throw new IllegalArgumentException();
            config = DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", url));
        } catch (Exception invalid) { throw new IllegalStateException("Use the isolated DAO test runner; no application DB fallback."); }
        try (Connection c = observer()) {
            assertEquals("EZam isolated DAO test T1.3", text(c,
                    "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()"));
            proof.put("serverVersion", text(c, "SHOW server_version_num"));
        }
        pool = DBUtil.open(config, 4, 2000); tx = new TransactionManager(pool::getConnection);
        proof.put("isolation", "fresh loopback PostgreSQL cluster; dedicated marked database");
    }

    private static Connection observer() throws SQLException {
        var properties = config.toConnectionProperties(); properties.setProperty("ApplicationName", "ezam-t13-observer");
        return DriverManager.getConnection(config.getJdbcUrl(), properties);
    }

    private static String text(Connection c, String sql, Object... params) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }

    private static int number(Connection c, String sql, Object... params) throws SQLException {
        return Integer.parseInt(text(c, sql, params));
    }

    private static String id() { return UUID.randomUUID().toString(); }

    private static GiaoVien teacher() {
        var v = new GiaoVien(); v.setIdGv(id()); v.setEmail(id() + "@example.test");
        v.setMatKhau("synthetic-hash-for-storage-test-only"); v.setHoTen("Giáo viên Nguyễn Ánh"); return v;
    }

    private static DeThi exam(GiaoVien teacher) {
        var e = new DeThi(); e.setIdDeThi(id()); e.setIdGv(teacher.getIdGv()); e.setTenDeThi("Đề tiếng Việt");
        e.setKhoi("6"); e.setSoLuongCauHoi(0); e.setKieuTaiLieu("TEXT");
        e.setNgayTao(LocalDate.of(2026, 10, 18)); e.setNoiDungTaiLieu("Tài liệu UTF-8 🌿"); return e;
    }

    private static List<CauHoi> questions(DeThi e, int count) {
        List<CauHoi> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var q = new CauHoi(); q.setIdCauHoi(id()); q.setIdDeThi(e.getIdDeThi());
            q.setNoiDung("Câu hỏi " + i); q.setDapAnA("Nước"); q.setDapAnB("Đất");
            q.setDapAnC("Không khí"); q.setDapAnD("Lửa"); q.setDapAnDung("A"); list.add(q);
        }
        return list;
    }

    private static PhongThi room(GiaoVien teacher, DeThi exam) {
        var r = new PhongThi(); r.setIdPhongThi(id()); r.setIdGv(teacher.getIdGv()); r.setIdDeThi(exam.getIdDeThi());
        r.setMaCodePhongThi(String.format("T%05d", codes.incrementAndGet())); r.setTenLop("Lớp 6A");
        r.setThoiGianLamBai(30); r.setNgayTaoPhong(LocalDate.of(2026, 10, 18));
        r.setTrangThaiPhong(TrangThaiPhongThi.DANG_CHO); return r;
    }

    private static HocSinh student(PhongThi room) {
        var s = new HocSinh(); s.setIdHs(id()); s.setHoVaTen("Thí sinh Trần An");
        s.setMaCodePhongThi(room.getMaCodePhongThi()); return s;
    }

    private static KetQuaThi result(HocSinh student, PhongThi room) {
        var k = new KetQuaThi(); k.setIdKq(id()); k.setIdHs(student.getIdHs()); k.setIdPhongThi(room.getIdPhongThi());
        k.setDiemSo(0.0); k.setTrangThai(TrangThaiBaiLam.DANG_THI); return k;
    }

    private record Fixture(GiaoVien teacher, DeThi exam, PhongThi room, HocSinh student, KetQuaThi result, List<CauHoi> questions) { }

    private static Fixture seed() throws SQLException {
        var t = teacher(); var e = exam(t); var q = questions(e, 3); var r = room(t, e); var s = student(r); var k = result(s, r);
        tx.execute(c -> {
            teachers.insert(c, t); exams.insert(c, e); assertEquals(APPLIED, exams.replaceOwnedQuestions(c, t.getIdGv(), e.getIdDeThi(), q));
            assertTrue(rooms.insertOwned(c, t.getIdGv(), r)); students.insert(c, s); assertTrue(submissions.insert(c, k)); return null;
        });
        return new Fixture(t, e, r, s, k, q);
    }

    @Test void testSchemaMatchesAllThirtyEightBaselineColumnsAndConstraints() throws Exception {
        try (Connection c = observer(); var resource = PostgresDaoIT.class.getResourceAsStream("/model/schema-baseline.json")) {
            assertNotNull(resource);
            var snapshot = JsonParser.parseReader(new InputStreamReader(resource, StandardCharsets.UTF_8)).getAsJsonObject();
            int count = 0;
            for (var entry : snapshot.getAsJsonArray("tables")) {
                var table = entry.getAsJsonObject();
                Map<String, List<Object>> actual = new LinkedHashMap<>();
                Map<String, String> foreignKeys = new LinkedHashMap<>();
                var primaryKeys = new java.util.HashSet<String>();
                String tableName = table.get("tableName").getAsString();
                try (var keys = c.getMetaData().getImportedKeys(null, "public", tableName)) {
                    while (keys.next()) {
                        foreignKeys.put(keys.getString("FKCOLUMN_NAME"), keys.getString("PKTABLE_NAME") + "." + keys.getString("PKCOLUMN_NAME"));
                        assertEquals(java.sql.DatabaseMetaData.importedKeyNoAction, keys.getInt("DELETE_RULE"));
                    }
                }
                try (var keys = c.getMetaData().getPrimaryKeys(null, "public", tableName)) {
                    while (keys.next()) primaryKeys.add(keys.getString("COLUMN_NAME"));
                }
                try (var columns = c.getMetaData().getColumns(null, "public", table.get("tableName").getAsString().replace("_", "\\_"), null)) {
                    while (columns.next()) {
                        assertNull(columns.getString("COLUMN_DEF"));
                        actual.put(columns.getString("COLUMN_NAME"), List.of(columns.getString("TYPE_NAME"), columns.getInt("NULLABLE") == 1));
                    }
                }
                assertEquals(table.getAsJsonArray("columns").size(), actual.size());
                for (var item : table.getAsJsonArray("columns")) {
                    var expected = item.getAsJsonObject();
                    String name = expected.get("column").getAsString();
                    assertEquals(List.of(expected.get("postgresType").getAsString(), expected.get("nullable").getAsBoolean()), actual.get(name));
                    assertEquals(expected.get("primaryKey").getAsBoolean(), primaryKeys.contains(name));
                    assertEquals(expected.get("foreignKey").isJsonNull() ? null : expected.get("foreignKey").getAsString(), foreignKeys.get(name));
                    count++;
                }
            }
            assertEquals(38, count);
            assertEquals(6, number(c, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"));
            assertEquals(7, number(c, "SELECT count(*) FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND contype = 'f' AND confdeltype = 'a'"));
            assertEquals(2, number(c, "SELECT count(*) FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND contype = 'u'"));
            proof.put("catalog", Map.of("columns", count, "foreignKeysNoAction", 7, "uniqueConstraints", 2, "serverDefaults", 0));
        }
    }

    @Test void teacherReadProfileAndHashWritesRemainScoped() throws Exception {
        var f = seed();
        tx.execute(c -> {
            var t = teachers.findByEmail(c, f.teacher.getEmail()).orElseThrow();
            assertEquals(f.teacher.getIdGv(), t.getIdGv()); assertEquals(f.teacher.getMatKhau(), t.getMatKhau());
            assertEquals("Giáo viên Nguyễn Ánh", t.getHoTen()); assertNull(t.getTruong()); assertNull(t.getTinh());
            assertTrue(teachers.lockById(c, t.getIdGv()).isPresent());
            assertTrue(teachers.updateProfile(c, t.getIdGv(), "Tên mới", "Trường B", "Huế"));
            assertTrue(teachers.updatePasswordHash(c, t.getIdGv(), "synthetic-new-hash"));
            var changed = teachers.findById(c, t.getIdGv()).orElseThrow();
            assertEquals("Tên mới", changed.getHoTen()); assertEquals("Trường B", changed.getTruong()); assertEquals("Huế", changed.getTinh());
            assertEquals(t.getEmail(), changed.getEmail()); assertEquals("synthetic-new-hash", changed.getMatKhau());
            assertFalse(teachers.updateProfile(c, "missing", "x", null, null));
            assertTrue(teachers.findById(c, "missing").isEmpty()); return null;
        });
    }

    @Test void examCrudReplacesChildrenAndDeletesChildFirst() throws Exception {
        var t = teacher(); var e = exam(t);
        tx.execute(c -> {
            teachers.insert(c, t); exams.insert(c, e);
            assertEquals(APPLIED, exams.replaceOwnedQuestions(c, t.getIdGv(), e.getIdDeThi(), questions(e, 3)));
            e.setTenDeThi("Đề đã sửa"); assertEquals(APPLIED, exams.updateOwnedMetadata(c, t.getIdGv(), e));
            assertEquals(APPLIED, exams.replaceOwnedQuestions(c, t.getIdGv(), e.getIdDeThi(), questions(e, 2)));
            var detail = exams.findOwned(c, t.getIdGv(), e.getIdDeThi()).orElseThrow();
            assertEquals("Đề đã sửa", detail.getTenDeThi()); assertEquals(2, detail.getSoLuongCauHoi());
            assertEquals(e.getNgayTao(), detail.getNgayTao()); assertEquals(e.getNoiDungTaiLieu(), detail.getNoiDungTaiLieu());
            assertEquals(2, exams.listOwnedQuestions(c, t.getIdGv(), e.getIdDeThi()).size());
            assertNull(exams.listOwned(c, t.getIdGv()).get(0).getNoiDungTaiLieu());
            assertEquals(APPLIED, exams.deleteOwned(c, t.getIdGv(), e.getIdDeThi()));
            assertTrue(exams.findOwned(c, t.getIdGv(), e.getIdDeThi()).isEmpty()); return null;
        });
        try (Connection c = observer()) { assertEquals(0, number(c, "SELECT count(*) FROM cau_hoi WHERE id_de_thi = ?", e.getIdDeThi())); }
        proof.put("examCrud", "replace/delete use explicit child-first SQL in the same Service transaction");
    }

    @Test void nullableFieldsRoundTripWithoutInventedDefaults() throws Exception {
        var t = teacher(); var e = exam(t); e.setKhoi(null); e.setSoLuongCauHoi(null); e.setNoiDungTaiLieu(null); e.setNgayTao(null);
        var r = room(t, e); r.setNgayTaoPhong(null); r.setTrangThaiPhong(null);
        var s = student(r); var k = result(s, r); k.setDiemSo(null); k.setTrangThai(null);
        tx.execute(c -> {
            teachers.insert(c, t); exams.insert(c, e); assertTrue(rooms.insertOwned(c, t.getIdGv(), r)); students.insert(c, s);
            assertTrue(submissions.insert(c, k));
            var found = exams.findOwned(c, t.getIdGv(), e.getIdDeThi()).orElseThrow();
            assertNull(found.getSoLuongCauHoi()); assertNull(found.getNgayTao()); assertNull(found.getKhoi()); assertNull(found.getNoiDungTaiLieu());
            var foundRoom = rooms.findByCode(c, r.getMaCodePhongThi()).orElseThrow();
            assertNull(foundRoom.getNgayTaoPhong()); assertNull(foundRoom.getTrangThaiPhong());
            var foundResult = submissions.findInRoom(c, s.getIdHs(), r.getIdPhongThi()).orElseThrow();
            assertNull(foundResult.getDiemSo()); assertNull(foundResult.getTrangThai()); return null;
        });
    }

    @Test void otherOwnerCannotReadChangeOrDeleteExamOrRoom() throws Exception {
        var f = seed(); String other = id();
        tx.execute(c -> {
            assertTrue(exams.listOwned(c, other).isEmpty()); assertTrue(exams.findOwned(c, other, f.exam.getIdDeThi()).isEmpty());
            assertTrue(exams.listOwnedQuestions(c, other, f.exam.getIdDeThi()).isEmpty());
            assertEquals(NOT_FOUND, exams.updateOwnedMetadata(c, other, f.exam));
            assertEquals(NOT_FOUND, exams.replaceOwnedQuestions(c, other, f.exam.getIdDeThi(), List.of()));
            assertEquals(NOT_FOUND, exams.deleteOwned(c, other, f.exam.getIdDeThi()));
            assertTrue(rooms.findOwned(c, other, f.room.getIdPhongThi()).isEmpty());
            assertFalse(rooms.deleteOwned(c, other, f.room.getIdPhongThi()));
            assertFalse(rooms.updateStateOwned(c, other, f.room.getIdPhongThi(), TrangThaiPhongThi.DANG_CHO, TrangThaiPhongThi.DA_DONG));
            assertTrue(students.listByOwnedRoom(c, other, f.room.getIdPhongThi()).isEmpty());
            assertTrue(submissions.listByOwnedRoom(c, other, f.room.getIdPhongThi()).isEmpty());
            var forged = room(f.teacher, f.exam); assertFalse(rooms.insertOwned(c, other, forged)); return null;
        });
        proof.put("ownership", "teacher/exam/room/admission predicates verified against another owner");
    }

    @Test void referencedExamRejectsMetadataQuestionsAndDeleteWithoutChanges() throws Exception {
        var f = seed(); f.exam.setTenDeThi("Forbidden edit");
        tx.execute(c -> {
            assertTrue(exams.isReferenced(c, f.exam.getIdDeThi()));
            assertEquals(REFERENCED, exams.updateOwnedMetadata(c, f.teacher.getIdGv(), f.exam));
            assertEquals(REFERENCED, exams.replaceOwnedQuestions(c, f.teacher.getIdGv(), f.exam.getIdDeThi(), List.of()));
            assertEquals(REFERENCED, exams.deleteOwned(c, f.teacher.getIdGv(), f.exam.getIdDeThi()));
            assertEquals("Đề tiếng Việt", exams.findOwned(c, f.teacher.getIdGv(), f.exam.getIdDeThi()).orElseThrow().getTenDeThi());
            assertEquals(3, exams.listOwnedQuestions(c, f.teacher.getIdGv(), f.exam.getIdDeThi()).size()); return null;
        });
        proof.put("referencedExam", "REFERENCED for all three mutations; HTTP 409 mapping belongs to future Service/Servlet");
    }

    @Test void failureAtThirdQuestionRollsBackNewExamAndEarlierChildren() throws Exception {
        var t = teacher(); tx.execute(c -> { teachers.insert(c, t); return null; }); var e = exam(t); var q = questions(e, 3); q.get(2).setNoiDung(null);
        SQLException failure = assertThrows(SQLException.class, () -> tx.execute(c -> {
            exams.insert(c, e); exams.replaceOwnedQuestions(c, t.getIdGv(), e.getIdDeThi(), q); return null;
        }));
        assertEquals("23502", failure.getSQLState());
        try (Connection c = observer()) {
            assertEquals(0, number(c, "SELECT count(*) FROM de_thi WHERE id_de_thi = ?", e.getIdDeThi()));
            assertEquals(0, number(c, "SELECT count(*) FROM cau_hoi WHERE id_de_thi = ?", e.getIdDeThi()));
        }
        proof.put("questionRollback", Map.of("sqlState", "23502", "remainingExams", 0, "remainingQuestions", 0));
    }

    @Test void failedQuestionReplacementRestoresOldMetadataAndChildren() throws Exception {
        var t = teacher(); var e = exam(t); var old = questions(e, 3);
        tx.execute(c -> { teachers.insert(c, t); exams.insert(c, e); exams.replaceOwnedQuestions(c, t.getIdGv(), e.getIdDeThi(), old); return null; });
        var bad = questions(e, 2); bad.get(1).setDapAnB(null); e.setTenDeThi("Rollback this title");
        assertThrows(SQLException.class, () -> tx.execute(c -> {
            exams.updateOwnedMetadata(c, t.getIdGv(), e); exams.replaceOwnedQuestions(c, t.getIdGv(), e.getIdDeThi(), bad); return null;
        }));
        try (Connection c = observer()) {
            assertEquals("Đề tiếng Việt", exams.findOwned(c, t.getIdGv(), e.getIdDeThi()).orElseThrow().getTenDeThi());
            assertEquals(old.stream().map(CauHoi::getIdCauHoi).sorted().toList(), exams.listOwnedQuestions(c, t.getIdGv(), e.getIdDeThi()).stream().map(CauHoi::getIdCauHoi).toList());
        }
    }

    @Test void failureAfterSuccessfulDeleteRestoresParentAndChildren() throws Exception {
        var t = teacher(); var e = exam(t);
        tx.execute(c -> { teachers.insert(c, t); exams.insert(c, e); exams.replaceOwnedQuestions(c, t.getIdGv(), e.getIdDeThi(), questions(e, 3)); return null; });
        assertThrows(IllegalStateException.class, () -> tx.execute(c -> {
            assertEquals(APPLIED, exams.deleteOwned(c, t.getIdGv(), e.getIdDeThi())); throw new IllegalStateException("Synthetic post-delete validation failure");
        }));
        try (Connection c = observer()) { assertTrue(exams.findOwned(c, t.getIdGv(), e.getIdDeThi()).isPresent()); assertEquals(3, exams.listOwnedQuestions(c, t.getIdGv(), e.getIdDeThi()).size()); }
    }

    @Test void joinPairUsesOneTransactionAndResultFailureRemovesStudent() throws Exception {
        var f = seed(); var s = student(f.room); var k = result(s, f.room); k.setIdKq(null);
        SQLException error = assertThrows(SQLException.class, () -> tx.execute(c -> {
            rooms.lockByCode(c, f.room.getMaCodePhongThi()).orElseThrow(); students.insert(c, s);
            submissions.insert(c, k); return null;
        }));
        assertEquals("23502", error.getSQLState());
        try (Connection c = observer()) { assertTrue(students.findInRoom(c, s.getIdHs(), f.room.getIdPhongThi()).isEmpty()); assertTrue(submissions.findInRoom(c, s.getIdHs(), f.room.getIdPhongThi()).isEmpty()); }
        proof.put("joinRollback", Map.of("sqlState", "23502", "remainingNewStudents", 0, "remainingNewResults", 0));
    }

    @Test void admissionUsesRoomCodeFkAndRejectsCrossRoomResult() throws Exception {
        var a = seed(); var b = seed();
        try (Connection c = observer()) {
            assertEquals(a.student.getHoVaTen(), students.findInRoom(c, a.student.getIdHs(), a.room.getIdPhongThi()).orElseThrow().getHoVaTen());
            assertTrue(students.findInRoom(c, a.student.getIdHs(), b.room.getIdPhongThi()).isEmpty());
            assertTrue(submissions.findInRoom(c, a.student.getIdHs(), b.room.getIdPhongThi()).isEmpty());
        }
        var wrong = result(a.student, b.room);
        boolean inserted = tx.execute(c -> submissions.insert(c, wrong));
        assertFalse(inserted);
        var absentRoom = student(a.room); absentRoom.setMaCodePhongThi("ZZZZZZ");
        SQLException fk = assertThrows(SQLException.class, () -> tx.execute(c -> { students.insert(c, absentRoom); return null; }));
        assertEquals("23503", fk.getSQLState()); assertFalse(SqlErrors.isRoomCodeConflict(fk));
    }

    @ParameterizedTest @EnumSource(TrangThaiPhongThi.class)
    void roomEnumRoundTripAndConditionalOwnerWrite(TrangThaiPhongThi state) throws Exception {
        var f = seed();
        tx.execute(c -> {
            assertTrue(rooms.updateStateOwned(c, f.teacher.getIdGv(), f.room.getIdPhongThi(), TrangThaiPhongThi.DANG_CHO, state));
            var r = rooms.findOwned(c, f.teacher.getIdGv(), f.room.getIdPhongThi()).orElseThrow();
            assertEquals(state, r.getTrangThaiPhong()); assertEquals(f.room.getNgayTaoPhong(), r.getNgayTaoPhong());
            assertEquals(30, r.getThoiGianLamBai()); assertEquals(f.exam.getIdDeThi(), r.getIdDeThi());
            assertEquals(f.teacher.getIdGv(), r.getIdGv()); assertEquals("Lớp 6A", r.getTenLop()); return null;
        });
    }

    @ParameterizedTest @EnumSource(TrangThaiBaiLam.class)
    void resultEnumAndDoubleRoundTrip(TrangThaiBaiLam state) throws Exception {
        var f = seed(); f.result.setTrangThai(state); f.result.setDiemSo(6.67);
        tx.execute(c -> {
            assertTrue(submissions.updateUnfinished(c, f.result));
            var value = submissions.findInRoom(c, f.student.getIdHs(), f.room.getIdPhongThi()).orElseThrow();
            assertEquals(state, value.getTrangThai()); assertEquals(6.67, value.getDiemSo()); assertEquals(f.result.getIdKq(), value.getIdKq()); return null;
        });
    }

    @Test void completedResultCannotBeOverwrittenOrDowngraded() throws Exception {
        var f = seed(); f.result.setTrangThai(TrangThaiBaiLam.HOAN_THANH); f.result.setDiemSo(8.5);
        tx.execute(c -> { assertTrue(submissions.updateUnfinished(c, f.result)); return null; });
        f.result.setDiemSo(0.0); f.result.setTrangThai(TrangThaiBaiLam.MAT_KET_NOI);
        boolean updated = tx.execute(c -> submissions.updateUnfinished(c, f.result));
        assertFalse(updated);
        try (Connection c = observer()) {
            var value = submissions.findInRoom(c, f.student.getIdHs(), f.room.getIdPhongThi()).orElseThrow();
            assertEquals(8.5, value.getDiemSo()); assertEquals(TrangThaiBaiLam.HOAN_THANH, value.getTrangThai());
        }
    }

    @Test void duplicateAdmissionResultsFailInsteadOfPretendingUniqueConstraintExists() throws Exception {
        var f = seed(); var duplicate = result(f.student, f.room);
        tx.execute(c -> { assertTrue(submissions.insert(c, duplicate)); return null; });
        try (Connection c = observer()) {
            assertEquals("21000", assertThrows(SQLException.class,
                    () -> submissions.findInRoom(c, f.student.getIdHs(), f.room.getIdPhongThi())).getSQLState());
        }
        proof.put("noInventedOneToOne", "duplicate rows allowed by schema; singular DAO lookup fails with 21000");
    }

    @Test void exactUniqueConstraintsAreClassifiedAndPrimaryKeyIsNotRetried() throws Exception {
        var f = seed(); var duplicate = teacher(); duplicate.setEmail(f.teacher.getEmail());
        SQLException email = assertThrows(SQLException.class, () -> tx.execute(c -> { teachers.insert(c, duplicate); return null; }));
        assertEquals("23505", email.getSQLState()); assertTrue(SqlErrors.isEmailConflict(email)); assertFalse(SqlErrors.isRoomCodeConflict(email));
        var otherRoom = room(f.teacher, f.exam); otherRoom.setMaCodePhongThi(f.room.getMaCodePhongThi());
        SQLException code = assertThrows(SQLException.class, () -> tx.execute(c -> rooms.insertOwned(c, f.teacher.getIdGv(), otherRoom)));
        assertEquals("23505", code.getSQLState()); assertTrue(SqlErrors.isRoomCodeConflict(code)); assertFalse(SqlErrors.isEmailConflict(code));
        SQLException primary = assertThrows(SQLException.class, () -> tx.execute(c -> { teachers.insert(c, f.teacher); return null; }));
        assertFalse(SqlErrors.isEmailConflict(primary)); assertFalse(SqlErrors.isRoomCodeConflict(primary));
        var wrapped = new SQLException("batch wrapper"); wrapped.setNextException(code); assertTrue(SqlErrors.isRoomCodeConflict(wrapped));
        proof.put("uniqueClassification", "23505 + exact table/constraint; PK/FK/NOT NULL not classified as room-code retry");
    }

    @Test void publicQuestionProjectionAndPrivateAnswerLookupAreSeparated() throws Exception {
        var f = seed();
        try (Connection c = observer()) {
            var publicRows = exams.listPublicQuestions(c, f.exam.getIdDeThi()); assertEquals(3, publicRows.size());
            var json = JsonParser.parseString(new GsonBuilder().create().toJson(publicRows)).getAsJsonArray();
            for (var value : json) {
                assertEquals(java.util.Set.of("idCauHoi", "noiDung", "dapAnA", "dapAnB", "dapAnC", "dapAnD"), value.getAsJsonObject().keySet());
            }
            var privateRows = exams.listCorrectAnswers(c, f.exam.getIdDeThi()); assertEquals(3, privateRows.size());
            assertTrue(privateRows.stream().allMatch(value -> value.dapAnDung().equals("A")));
            assertTrue(exams.listPublicQuestions(c, "missing").isEmpty());
        }
        proof.put("publicQuestions", "SQL whitelist and six-field detached projection omit answer key");
    }

    @Test void boundParametersPreventSqlInjection() throws Exception {
        var f = seed(); String payload = "' OR 1=1 --";
        try (Connection c = observer()) {
            assertTrue(teachers.findByEmail(c, payload).isEmpty()); assertTrue(exams.listOwned(c, payload).isEmpty());
            assertTrue(exams.findOwned(c, f.teacher.getIdGv(), payload).isEmpty()); assertTrue(rooms.findByCode(c, payload).isEmpty());
            assertTrue(students.findInRoom(c, payload, f.room.getIdPhongThi()).isEmpty()); assertTrue(submissions.findInRoom(c, payload, f.room.getIdPhongThi()).isEmpty());
        }
    }

    @Test void roomDeleteRemovesResultsThenStudentsAndPreservesOtherRoom() throws Exception {
        var a = seed(); var b = seed();
        tx.execute(c -> { assertTrue(rooms.deleteOwned(c, a.teacher.getIdGv(), a.room.getIdPhongThi())); return null; });
        try (Connection c = observer()) {
            assertFalse(rooms.existsCode(c, a.room.getMaCodePhongThi()));
            assertEquals(0, number(c, "SELECT count(*) FROM hoc_sinh WHERE id_hs = ?", a.student.getIdHs()));
            assertEquals(0, number(c, "SELECT count(*) FROM ket_qua_thi WHERE id_kq = ?", a.result.getIdKq()));
            assertTrue(rooms.findOwned(c, b.teacher.getIdGv(), b.room.getIdPhongThi()).isPresent());
            assertEquals(1, students.listByOwnedRoom(c, b.teacher.getIdGv(), b.room.getIdPhongThi()).size());
            assertEquals(1, submissions.listByOwnedRoom(c, b.teacher.getIdGv(), b.room.getIdPhongThi()).size());
            assertTrue(exams.findOwned(c, a.teacher.getIdGv(), a.exam.getIdDeThi()).isPresent());
        }
    }

    @Test void resultUpdateRejectsWrongRoomWithoutChangingStoredGrade() throws Exception {
        var a = seed(); var b = seed(); a.result.setIdPhongThi(b.room.getIdPhongThi());
        a.result.setDiemSo(10.0); a.result.setTrangThai(TrangThaiBaiLam.HOAN_THANH);
        boolean changed = tx.execute(c -> submissions.updateUnfinished(c, a.result)); assertFalse(changed);
        try (Connection c = observer()) {
            var stored = submissions.findInRoom(c, a.student.getIdHs(), a.room.getIdPhongThi()).orElseThrow();
            assertEquals(0.0, stored.getDiemSo()); assertEquals(TrangThaiBaiLam.DANG_THI, stored.getTrangThai());
        }
    }

    @Test void inconsistentCrossRoomForeignKeysFailClosedOnRoomDeletion() throws Exception {
        var a = seed(); var b = seed();
        // The existing schema permits these FK values separately; it has no composite admission FK.
        tx.execute(c -> {
            try (var statement = c.prepareStatement("INSERT INTO ket_qua_thi(id_kq, id_hs, id_phong_thi) VALUES (?, ?, ?)")) {
                statement.setString(1, id()); statement.setString(2, a.student.getIdHs()); statement.setString(3, b.room.getIdPhongThi());
                assertEquals(1, statement.executeUpdate());
            }
            return null;
        });
        SQLException error = assertThrows(SQLException.class, () -> tx.execute(c -> rooms.deleteOwned(c, a.teacher.getIdGv(), a.room.getIdPhongThi())));
        assertEquals("23503", error.getSQLState());
        try (Connection c = observer()) {
            assertTrue(rooms.findByCode(c, a.room.getMaCodePhongThi()).isPresent());
            assertTrue(students.findInRoom(c, a.student.getIdHs(), a.room.getIdPhongThi()).isPresent());
            assertTrue(submissions.findInRoom(c, a.student.getIdHs(), a.room.getIdPhongThi()).isPresent());
            assertEquals(2, number(c, "SELECT count(*) FROM ket_qua_thi WHERE id_phong_thi = ?", b.room.getIdPhongThi()));
        }
        proof.put("inconsistentCrossRoomData", "FK error rolls back room deletion; other room results are never broadly deleted");
    }

    private static void lock(Connection c, String entity, Fixture f) throws SQLException {
        switch (entity) {
            case "teacher" -> teachers.lockById(c, f.teacher.getIdGv()).orElseThrow();
            case "exam" -> exams.lockOwned(c, f.teacher.getIdGv(), f.exam.getIdDeThi()).orElseThrow();
            case "room" -> rooms.lockByCode(c, f.room.getMaCodePhongThi()).orElseThrow();
            case "student" -> students.lockInRoom(c, f.student.getIdHs(), f.room.getIdPhongThi()).orElseThrow();
            case "result" -> submissions.lockInRoom(c, f.student.getIdHs(), f.room.getIdPhongThi()).orElseThrow();
            default -> throw new IllegalArgumentException();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"teacher", "exam", "room", "student", "result"})
    void realForUpdateBlocksCompetingConnectionUntilTransactionEnds(String entity) throws Exception {
        var f = seed();
        try (Connection first = pool.getConnection(); Connection second = pool.getConnection()) {
            first.setAutoCommit(false); second.setAutoCommit(false);
            try {
                lock(first, entity, f); text(second, "SELECT set_config('lock_timeout', '150ms', true)");
                assertEquals("55P03", assertThrows(SQLException.class, () -> lock(second, entity, f)).getSQLState());
                second.rollback(); first.rollback(); lock(second, entity, f); second.rollback();
            } finally { first.rollback(); second.rollback(); }
        }
        proof.put("rowLock-" + entity, "second connection blocked (55P03); succeeds after rollback releases lock");
    }

    @Test void roomCreationAndExamMutationCoordinateUsingSameParentLock() throws Exception {
        var t = teacher(); var e = exam(t); var r = room(t, e);
        tx.execute(c -> { teachers.insert(c, t); exams.insert(c, e); return null; });
        try (Connection creator = pool.getConnection(); Connection editor = pool.getConnection()) {
            creator.setAutoCommit(false); editor.setAutoCommit(false);
            try {
                assertTrue(rooms.insertOwned(creator, t.getIdGv(), r)); text(editor, "SELECT set_config('lock_timeout', '150ms', true)");
                assertEquals("55P03", assertThrows(SQLException.class, () -> exams.deleteOwned(editor, t.getIdGv(), e.getIdDeThi())).getSQLState());
                editor.rollback(); creator.commit();
                assertEquals(REFERENCED, exams.deleteOwned(editor, t.getIdGv(), e.getIdDeThi()));
                assertEquals(REFERENCED, exams.updateOwnedMetadata(editor, t.getIdGv(), e)); editor.rollback();
            } finally { creator.rollback(); editor.rollback(); }
        }
        proof.put("createRoomVsExamMutation", "parent row lock serializes; committed room reference prevents delete/edit");
    }

    @AfterAll static void shutdownAndProof() throws Exception {
        if (pool != null) {
            pool.close(); assertTrue(pool.isClosed());
            try (Connection c = observer()) { assertEquals(0, number(c, "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'ezam-t13-pool'")); }
            proof.put("poolShutdown", "closed; no remaining pooled PostgreSQL sessions");
        }
        Path folder = Path.of("target/verification"); Files.createDirectories(folder);
        Files.writeString(folder.resolve("T1_3_postgres.json"), new GsonBuilder().setPrettyPrinting().create().toJson(proof));
    }
}
