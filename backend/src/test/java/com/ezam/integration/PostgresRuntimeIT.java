package com.ezam.integration;

import com.ezam.dao.*;
import com.ezam.model.*;
import com.ezam.model.runtime.RuntimeData.*;
import com.ezam.service.*;
import com.ezam.util.*;
import com.ezam.util.runtime.*;
import java.net.URI;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PostgreSQL commit/rollback + real sidecars in @TempDir. No HTTP/Socket/grade algorithm. */
class PostgresRuntimeIT {
    @TempDir Path directory;
    private static DBUtil pool;
    private static TransactionManager transactions;
    private static RuntimeDatabaseEvidence evidence;
    private static final Map<String, Object> proof = new LinkedHashMap<>();
    private static final AtomicInteger sequence = new AtomicInteger();
    private static final RoomDAO rooms = new RoomDAO();
    private static final SubmissionDAO results = new SubmissionDAO();

    @BeforeAll static void setup() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        try {
            URI uri = new URI(url == null ? "" : url);
            if (!"postgresql".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || !"/ezam_t14_test".equals(uri.getPath())) throw new IllegalArgumentException();
            var config = DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", url));
            try (var c = java.sql.DriverManager.getConnection(config.getJdbcUrl(), config.toConnectionProperties());
                    var statement = c.prepareStatement("SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()");
                    var rows = statement.executeQuery()) {
                assertTrue(rows.next()); assertEquals("EZam isolated runtime test T1.4", rows.getString(1));
            }
            pool = DBUtil.open(config, 3, 2000); transactions = new TransactionManager(pool::getConnection);
            evidence = new RuntimeDatabaseEvidence(transactions);
        } catch (Exception invalid) { throw new IllegalStateException("Use the isolated runtime test runner."); }
    }
    private record Fixture(String teacher, String room, String code, String student, Run run) { }
    private static Fixture seed() throws Exception {
        String teacherId = UUID.randomUUID().toString(), examId = UUID.randomUUID().toString(), roomId = UUID.randomUUID().toString(), studentId = UUID.randomUUID().toString();
        String code = String.format("R%05d", sequence.incrementAndGet());
        var teacher = new GiaoVien(); teacher.setIdGv(teacherId); teacher.setEmail(teacherId + "@example.test"); teacher.setMatKhau("storage-fixture-only"); teacher.setHoTen("Runtime fixture");
        var exam = new DeThi(); exam.setIdDeThi(examId); exam.setIdGv(teacherId); exam.setTenDeThi("Fixture"); exam.setKieuTaiLieu("TEXT"); exam.setNgayTao(LocalDate.of(2026, 10, 18)); exam.setSoLuongCauHoi(1);
        var room = new PhongThi(); room.setIdPhongThi(roomId); room.setIdDeThi(examId); room.setIdGv(teacherId); room.setMaCodePhongThi(code); room.setTenLop("6A"); room.setThoiGianLamBai(1); room.setTrangThaiPhong(TrangThaiPhongThi.DANG_CHO);
        var student = new HocSinh(); student.setIdHs(studentId); student.setHoVaTen("Nguyễn An"); student.setMaCodePhongThi(code);
        var result = new KetQuaThi(); result.setIdKq(UUID.randomUUID().toString()); result.setIdHs(studentId); result.setIdPhongThi(roomId); result.setDiemSo(0.0); result.setTrangThai(TrangThaiBaiLam.DANG_THI);
        transactions.execute(c -> {
            new TeacherDAO().insert(c, teacher); new ExamDAO().insert(c, exam); assertTrue(rooms.insertOwned(c, teacherId, room));
            new StudentDAO().insert(c, student); assertTrue(results.insert(c, result)); return null;
        });
        Run run = new Run(UUID.randomUUID().toString(), roomId, examId, code, "Fixture", "6A", 1, "DANG_THI", 1000, 61000, 1000,
                List.of(new Question("question-id", "Question?", "A text", "B text", "C text", "D text")));
        return new Fixture(teacherId, roomId, code, studentId, run);
    }
    private static DbState after(DbState value, TrangThaiPhongThi room, TrangThaiBaiLam status, Double score) {
        return new DbState(value.idPhongThi(), value.maCodePhongThi(), value.idDeThi(), value.thoiGianLamBai(), room,
                value.idHs(), value.idKq(), status, score);
    }
    private static void failAtActualCommit(Connection c) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO runtime_commit_guard(id, ref) VALUES (?, ?)")) {
            s.setInt(1, 1000 + sequence.incrementAndGet()); s.setInt(2, -1); assertEquals(1, s.executeUpdate());
        }
    }
    private static void start(RuntimeStore store, Fixture f) throws Exception {
        DbState before = evidence.committed(f.code, null);
        new RuntimeTransactionService(store, transactions).execute(f.code, null, (c, unit, lockedBefore) -> {
            rooms.lockByCode(c, f.code).orElseThrow();
            unit.prepareRun(before, after(before, TrangThaiPhongThi.DANG_THI, null, null), f.run);
            assertTrue(rooms.updateStateOwned(c, f.teacher, f.room, TrangThaiPhongThi.DANG_CHO, TrangThaiPhongThi.DANG_THI)); return null;
        });
    }

    @Test void realCommitFailureDoesNotPublishPreparedDeadline() throws Exception {
        var f = seed(); DbState before = evidence.committed(f.code, null);
        try (RuntimeStore store = new RuntimeStore(directory, evidence)) {
            SQLException error = assertThrows(SQLException.class, () -> new RuntimeTransactionService(store, transactions).execute(f.code, null, (c, unit, lockedBefore) -> {
                rooms.lockByCode(c, f.code).orElseThrow(); unit.prepareRun(before, after(before, TrangThaiPhongThi.DANG_THI, null, null), f.run);
                rooms.updateStateOwned(c, f.teacher, f.room, TrangThaiPhongThi.DANG_CHO, TrangThaiPhongThi.DANG_THI);
                failAtActualCommit(c); return null;
            }));
            assertEquals("23503", error.getSQLState()); assertEquals(TrangThaiPhongThi.DANG_CHO, evidence.committed(f.code, null).roomState());
        }
        try (RuntimeStore reopened = new RuntimeStore(directory, evidence)) { assertTrue(reopened.readRun(f.code).isEmpty()); }
        proof.put("commitFailure", "PostgreSQL deferred FK failed at COMMIT (23503); DB DANG_CHO; restart did not serve pending run");
    }

    @Test void writeFailureRollsBackAlreadyUpdatedDatabase() throws Exception {
        var f = seed(); var before = evidence.committed(f.code, null);
        try (RuntimeStore store = new RuntimeStore(directory, evidence, (step, path) -> { if (step.equals("before-move")) throw new java.io.IOException(); })) {
            assertThrows(RuntimeUnavailableException.class, () -> new RuntimeTransactionService(store, transactions).execute(f.code, null, (c, unit, lockedBefore) -> {
                rooms.lockByCode(c, f.code).orElseThrow(); rooms.updateStateOwned(c, f.teacher, f.room, TrangThaiPhongThi.DANG_CHO, TrangThaiPhongThi.DANG_THI);
                unit.prepareRun(before, after(before, TrangThaiPhongThi.DANG_THI, null, null), f.run); return null;
            }));
        }
        assertEquals(TrangThaiPhongThi.DANG_CHO, evidence.committed(f.code, null).roomState());
        proof.put("fileFailure", "real room UPDATE rolled back when sidecar atomic write failed");
    }

    @Test void failureAfterCommitReconcilesWithoutNewDeadline() throws Exception {
        var f = seed(); AtomicBoolean fail = new AtomicBoolean(true);
        try (RuntimeStore store = new RuntimeStore(directory, evidence, (step, path) -> {
            if (step.equals("before-move") && path.getFileName().toString().equals("run-" + f.code + ".json") && fail.getAndSet(false)) throw new java.io.IOException();
        })) { assertThrows(RuntimeUnavailableException.class, () -> start(store, f)); }
        assertEquals(TrangThaiPhongThi.DANG_THI, evidence.committed(f.code, null).roomState());
        try (RuntimeStore reopened = new RuntimeStore(directory, evidence)) {
            Run actual = reopened.readRun(f.code).orElseThrow(); assertEquals(f.run.runId(), actual.runId()); assertEquals(61000, actual.endsAt());
        }
        proof.put("postCommitRestart", "DB commit retained; pending run promoted against fresh DB evidence; original runId/endsAt retained");
    }

    @Test void recoveryRollbackRestartAndVersionProgressUseActualDbState() throws Exception {
        var f = seed();
        try (RuntimeStore store = new RuntimeStore(directory, evidence)) {
            start(store, f); DbState before = evidence.committed(f.code, f.student);
            DbState disconnected = after(before, TrangThaiPhongThi.DANG_THI, TrangThaiBaiLam.MAT_KET_NOI, 0.0);
            var frozen = new Recovery(f.student, "Nguyễn An", f.code, f.room, TrangThaiBaiLam.MAT_KET_NOI, 1, 11000, 11000L, 50L);
            assertThrows(SQLException.class, () -> new RuntimeTransactionService(store, transactions).execute(f.code, f.student, (c, unit, lockedBefore) -> {
                rooms.lockByCode(c, f.code).orElseThrow(); var result = results.lockInRoom(c, f.student, f.room).orElseThrow();
                unit.prepareRecovery(before, disconnected, frozen); result.setTrangThai(TrangThaiBaiLam.MAT_KET_NOI); results.updateUnfinished(c, result);
                failAtActualCommit(c); return null;
            }));
            assertTrue(store.readRecovery(f.code, f.student).isEmpty());
            assertEquals(TrangThaiBaiLam.DANG_THI, evidence.committed(f.code, f.student).resultState());
            new RuntimeTransactionService(store, transactions).execute(f.code, f.student, (c, unit, lockedBefore) -> {
                rooms.lockByCode(c, f.code).orElseThrow(); var result = results.lockInRoom(c, f.student, f.room).orElseThrow();
                unit.prepareRecovery(before, disconnected, frozen); result.setTrangThai(TrangThaiBaiLam.MAT_KET_NOI); results.updateUnfinished(c, result); return null;
            });
        }
        try (RuntimeStore store = new RuntimeStore(directory, evidence)) {
            assertEquals(1, store.readRecovery(f.code, f.student).orElseThrow().stateVersion());
            DbState before = evidence.committed(f.code, f.student); DbState back = after(before, TrangThaiPhongThi.DANG_THI, TrangThaiBaiLam.DANG_THI, 0.0);
            new RuntimeTransactionService(store, transactions).execute(f.code, f.student, (c, unit, lockedBefore) -> {
                rooms.lockByCode(c, f.code).orElseThrow(); var result = results.lockInRoom(c, f.student, f.room).orElseThrow();
                unit.prepareRecovery(before, back, new Recovery(f.student, "Nguyễn An", f.code, f.room, TrangThaiBaiLam.DANG_THI, 2, 26000, 11000L, 50L));
                result.setTrangThai(TrangThaiBaiLam.DANG_THI); results.updateUnfinished(c, result); return null;
            });
            assertEquals(2, store.readRecovery(f.code, f.student).orElseThrow().stateVersion()); assertEquals(61000, store.readRun(f.code).orElseThrow().endsAt());
        }
        proof.put("recovery", "failed commit did not publish MAT_KET_NOI; committed version 1 survived restart, version 2 kept original deadline");
    }

    @Test void gradeMetadataSurvivesRestartAndMissingMetadataFailsClosed() throws Exception {
        var f = seed();
        try (RuntimeStore store = new RuntimeStore(directory, evidence)) {
            start(store, f);
            var receipt = store.receiveFirst(f.code, new Submission("first-id", f.run.runId(), f.student, 2000, false, "manual", false, Map.of("question-id", "A")));
            assertFalse(receipt.duplicate());
            DbState before = evidence.committed(f.code, f.student); DbState done = after(before, TrangThaiPhongThi.DANG_THI, TrangThaiBaiLam.HOAN_THANH, 10.0);
            new RuntimeTransactionService(store, transactions).execute(f.code, f.student, (c, unit, lockedBefore) -> {
                rooms.lockByCode(c, f.code).orElseThrow(); var row = results.lockInRoom(c, f.student, f.room).orElseThrow();
                unit.prepareGrade(before, done, new StoredGrade(f.room, f.run.runId(), new Grade(f.student, 10, 1, 1, TrangThaiBaiLam.HOAN_THANH)));
                row.setDiemSo(10.0); row.setTrangThai(TrangThaiBaiLam.HOAN_THANH); assertTrue(results.updateUnfinished(c, row)); return null;
            });
        }
        try (RuntimeStore store = new RuntimeStore(directory, evidence)) {
            assertEquals(1, store.readGrade(f.code, f.student).orElseThrow().result().soCauDung());
            assertEquals("A", store.readFirst(f.code, f.student).orElseThrow().answers().get("question-id"));
            try (var files = Files.list(directory)) { for (Path file : files.filter(p -> p.getFileName().toString().startsWith("grade-")).toList()) Files.delete(file); }
            assertEquals(503, assertThrows(RuntimeUnavailableException.class, () -> store.readGrade(f.code, f.student)).getStatusCode());
            assertEquals(10, evidence.committed(f.code, f.student).diemSo());
        }
        proof.put("grade", "first receipt and Grade survived restart; missing Grade returned safe 503 without changing DB score");
    }

    @Test void staleBeforeSnapshotCannotReplaceAnAlreadyCommittedRun() throws Exception {
        var f = seed(); DbState stale = evidence.committed(f.code, null);
        try (RuntimeStore store = new RuntimeStore(directory, evidence)) {
            start(store, f);
            Run replacement = new Run("forbidden-new-run", f.room, f.run.idDeThi(), f.code, "Fixture", "6A", 1,
                    "DANG_THI", 100000, 160000, 100000, f.run.cauHoiList());
            assertThrows(RuntimeUnavailableException.class, () -> new RuntimeTransactionService(store, transactions).execute(f.code, null, (c, unit, actualBefore) -> {
                unit.prepareRun(stale, after(stale, TrangThaiPhongThi.DANG_THI, null, null), replacement);
                return null;
            }));
            assertEquals(f.run.runId(), store.readRun(f.code).orElseThrow().runId());
            assertEquals(61000, store.readRun(f.code).orElseThrow().endsAt());
            assertFalse(Files.exists(directory.resolve("run-" + f.code + ".json.pending")));
        }
        proof.put("staleSnapshot", "coordinator bound actual locked DB state; stale before rejected, committed runId/deadline unchanged");
    }

    @AfterAll static void cleanup() throws Exception {
        if (pool != null) { pool.close(); assertTrue(pool.isClosed()); }
        proof.put("poolClosed", true);
        Path out = Path.of("target/verification"); Files.createDirectories(out);
        Files.writeString(out.resolve("T1_4_postgres.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(proof));
    }
}
