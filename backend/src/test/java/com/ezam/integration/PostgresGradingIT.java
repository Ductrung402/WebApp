package com.ezam.integration;

import com.ezam.dao.*;
import com.ezam.dto.*;
import com.ezam.dto.SubmissionDTO.*;
import com.ezam.model.*;
import com.ezam.model.runtime.RuntimeData.*;
import com.ezam.service.*;
import com.ezam.util.*;
import com.ezam.util.runtime.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PostgreSQL + filesystem, no HTTP/Socket grading adapter (T5.2), no application DB. */
class PostgresGradingIT {
    @TempDir Path directory;
    private static DBUtil pool;
    private static TransactionManager tx;
    private static String teacherId;
    private RuntimeStore store;
    private GradingService grading;
    private SocketFlowService flow;
    private SocketDTO.Member teacher, student;
    private String token, code;
    private Run run;
    private final MutableClock clock = new MutableClock();
    private final AtomicReference<String> fail = new AtomicReference<>("");
    private static final Map<String, Object> proof = new LinkedHashMap<>();
    static final class MutableClock extends Clock {
        final AtomicLong time = new AtomicLong(1_790_000_000_000L);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis()); }
        public long millis() { return time.get(); }
    }
    @BeforeAll static void setup() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL"); var uri = URI.create(url == null ? "" : url);
        if (!"127.0.0.1".equals(uri.getHost()) || !"/ezam_t21_test".equals(uri.getPath())) throw new IllegalStateException("Use isolated runner");
        pool = DBUtil.open(DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", url)), 4, 3000);
        tx = new TransactionManager(pool::getConnection);
        try (var c = pool.getConnection(); var s = c.prepareStatement("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()"); var r = s.executeQuery()) {
            assertTrue(r.next()); assertEquals("EZam isolated auth test T2.1", r.getString(1));
        }
        teacherId = UUID.randomUUID().toString(); var g = new GiaoVien(); g.setIdGv(teacherId);
        g.setEmail(teacherId + "@example.com"); g.setHoTen("Grading fixture"); g.setMatKhau("synthetic-unused-hash");
        tx.execute(c -> { new TeacherDAO().insert(c, g); return null; });
    }
    @AfterAll static void done() throws Exception {
        if (pool != null) pool.close();
        Files.writeString(Path.of("target/verification/T5_1_postgres.json"), SocketDTO.JSON.toJson(proof));
    }
    private void open() {
        store = new RuntimeStore(directory, new RuntimeDatabaseEvidence(tx), (step, path) -> {
            String name = path.getFileName().toString(); String match = fail.get();
            if (step.equals("before-write") && ((!match.isEmpty() && name.startsWith(match)) ||
                    (match.equals("publish-grade") && name.startsWith("grade-") && !name.endsWith(".pending"))))
                throw new java.io.IOException("synthetic filesystem failure");
        });
        var jwt = JwtUtil.fromEnvironment(Map.of("JWT_SECRET_KEY", "synthetic-grading-key-at-least-32-bytes"), clock);
        token = jwt.createAccessToken(teacherId); flow = new SocketFlowService(tx, store, jwt, clock);
        grading = new GradingService(tx, store, clock);
    }
    @BeforeEach void seed() { open(); seedExam(3); }
    private void seedExam(int count) {
        var questions = new ArrayList<ExamDTO.Question>();
        for (int i = 0; i < count; i++) questions.add(new ExamDTO.Question("Question " + i, "A option", "B option", "C option", "D option", "A"));
        var exam = new ExamService(tx, new ExamDAO(), clock).create(teacherId, new ExamDTO.Manual("Grading exam", null, "TEXT", null, questions));
        var rooms = new RoomService(tx, clock); var room = rooms.create(teacherId, new RoomDTO.Create(exam.idDeThi(), "Grading class", 1));
        code = room.maCodePhongThi(); var admission = rooms.join(new RoomDTO.Join("Grading student", code));
        teacher = flow.authorize(new SocketDTO.Join(code, "teacher", null), token);
        student = flow.authorize(new SocketDTO.Join(code, "student", admission.idHs()), null);
        run = flow.start(teacher, token);
    }
    @AfterEach void close() { if (store != null) store.close(); }
    private Request answers(int correct) {
        var answers = new ArrayList<Answer>();
        for (int i = 0; i < run.cauHoiList().size(); i++) answers.add(new Answer(run.cauHoiList().get(i).idCauHoi(), i < correct ? "A" : "B"));
        return new Request(student.idHs(), student.idPhongThi(), answers);
    }
    private KetQuaThi db() throws Exception { return tx.execute(c -> new SubmissionDAO().findInRoom(c, student.idHs(), student.idPhongThi()).orElseThrow()); }
    private void assertDb(double score, TrangThaiBaiLam state) throws Exception { assertEquals(score, db().getDiemSo()); assertEquals(state, db().getTrangThai()); }
    private void rename(String name) throws Exception {
        tx.execute(c -> { try (var s = c.prepareStatement("UPDATE hoc_sinh SET ho_va_ten=? WHERE id_hs=?")) { s.setString(1, name); s.setString(2, student.idHs()); s.executeUpdate(); } return null; });
    }
    private void closed() throws Exception {
        tx.execute(c -> { var room = new RoomDAO().lockByCode(c, code).orElseThrow(); assertTrue(new RoomDAO().updateStateOwned(c, teacherId, room.getIdPhongThi(), TrangThaiPhongThi.DANG_THI, TrangThaiPhongThi.DA_DONG)); return null; });
    }
    private Path file(String prefix) throws Exception {
        try (var paths = Files.list(directory)) { return paths.filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".json")).findFirst().orElseThrow(); }
    }
    @Test void sevenOfTenAndActualCountInsteadOfCachedExamCount() throws Exception {
        seedExam(10);
        tx.execute(c -> { try (var s = c.prepareStatement("UPDATE de_thi SET so_luong_cau_hoi=999 WHERE id_de_thi=?")) { s.setString(1, run.idDeThi()); s.executeUpdate(); } return null; });
        var result = grading.grade(answers(7)); assertEquals(7.0, result.result().diemSo()); assertEquals(7, result.result().soCauDung()); assertEquals(10, result.result().tongSoCau());
        assertFalse(result.receipt().duplicate()); assertDb(7.0, TrangThaiBaiLam.HOAN_THANH);
        assertEquals(result.result(), store.readGrade(code, student.idHs()).orElseThrow().result());
        assertEquals(TrangThaiBaiLam.HOAN_THANH, store.readRecovery(code, student.idHs()).orElseThrow().trangThai());
        String json = SocketDTO.JSON.toJson(result); assertFalse(json.contains("dapAnDung")); assertFalse(json.contains("answers"));
        proof.put("sevenOfTen", result.result());
    }
    @Test void oneOfThreeAndRetryDifferingAnswersKeepScoreReceiptAndFiles() throws Exception {
        var first = grading.grade(answers(1)); String snapshot = Files.readString(file("submission-")); String grade = Files.readString(file("grade-"));
        clock.time.addAndGet(12345); var retry = grading.grade(answers(3), run.runId(), "timeout");
        assertEquals(3.33, first.result().diemSo()); assertEquals(1, first.result().soCauDung()); assertEquals(first.result(), retry.result());
        assertEquals(first.receipt().submissionId(), retry.receipt().submissionId()); assertEquals(first.receipt().receivedAt(), retry.receipt().receivedAt());
        assertEquals("manual", retry.receipt().reason()); assertTrue(retry.receipt().duplicate());
        assertEquals(snapshot, Files.readString(file("submission-"))); assertEquals(grade, Files.readString(file("grade-"))); assertDb(3.33, TrangThaiBaiLam.HOAN_THANH);
        proof.put("oneOfThree", first.result()); proof.put("immutableReceiptAndAnswers", true);
    }
    @ParameterizedTest @ValueSource(strings = {"empty", "null", "wrong"}) void zeroScoreForMissingNullOrWrong(String mode) throws Exception {
        var request = mode.equals("empty") ? new Request(student.idHs(), student.idPhongThi(), List.of()) : mode.equals("null")
                ? new Request(student.idHs(), student.idPhongThi(), List.of(new Answer(run.cauHoiList().get(0).idCauHoi(), null))) : answers(0);
        var result = grading.grade(request); assertEquals(0, result.result().soCauDung()); assertEquals(3, result.result().tongSoCau()); assertDb(0.0, TrangThaiBaiLam.HOAN_THANH);
    }
    @Test void foreignIncludingNullAnswerRunAndAdmissionRejectedWithoutSnapshot() throws Exception {
        assertEquals(422, assertThrows(AuthException.class, () -> grading.grade(new Request(student.idHs(), student.idPhongThi(), List.of(new Answer("foreign", null))))).status());
        assertEquals(409, assertThrows(AuthException.class, () -> grading.grade(answers(1), "wrong-run", "manual")).status());
        assertEquals(404, assertThrows(AuthException.class, () -> grading.grade(new Request("not-admitted", student.idPhongThi(), List.of()))).status());
        assertEquals(404, assertThrows(AuthException.class, () -> grading.grade(new Request(student.idHs(), "other-room", List.of()))).status());
        assertTrue(store.readFirst(code, student.idHs()).isEmpty()); assertDb(0.0, TrangThaiBaiLam.DANG_THI);
    }
    @Test void simultaneousSeparateServiceInstancesReturnOneFirstResult() throws Exception {
        var workers = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            var a = workers.submit(() -> { gate.await(); return grading.grade(answers(1)); });
            var b = workers.submit(() -> { gate.await(); return new GradingService(tx, store, clock).grade(answers(3)); }); gate.countDown();
            var first = a.get(8, TimeUnit.SECONDS); var second = b.get(8, TimeUnit.SECONDS);
            assertEquals(first.result(), second.result()); assertEquals(first.receipt().submissionId(), second.receipt().submissionId());
            assertNotEquals(first.receipt().duplicate(), second.receipt().duplicate()); assertTrue(Set.of(3.33, 10.0).contains(first.result().diemSo()));
            assertDb(first.result().diemSo(), TrangThaiBaiLam.HOAN_THANH); proof.put("concurrentServicesFirstSnapshotWins", true);
        } finally { gate.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void actualDeferredCommitFailureRollsBackButKeepsFirstPrivateSnapshot() throws Exception {
        rename("fail-grading-commit"); assertEquals(503, assertThrows(AuthException.class, () -> grading.grade(answers(1))).status());
        assertDb(0.0, TrangThaiBaiLam.DANG_THI); assertTrue(store.readGrade(code, student.idHs()).isEmpty());
        var first = store.readFirst(code, student.idHs()).orElseThrow(); assertEquals(3, first.answers().size()); assertNull(flow.sync(student, null, clock.millis()).submission());
        rename("Grading student"); store.close(); open(); var retry = grading.grade(answers(3));
        assertEquals(3.33, retry.result().diemSo()); assertEquals(first.submissionId(), retry.receipt().submissionId()); assertTrue(retry.receipt().duplicate());
        assertDb(3.33, TrangThaiBaiLam.HOAN_THANH); proof.put("actualCommitFailureRollbackRestartOriginalSnapshot", true);
    }
    @ParameterizedTest @ValueSource(strings = {"submission-", "grade-", "recovery-"}) void fileFailureBeforeCommitDoesNotPublishGrade(String prefix) throws Exception {
        fail.set(prefix); assertEquals(503, assertThrows(AuthException.class, () -> grading.grade(answers(1))).status());
        assertDb(0.0, TrangThaiBaiLam.DANG_THI); assertTrue(store.readGrade(code, student.idHs()).isEmpty()); assertNull(flow.sync(student, null, clock.millis()).submission());
        fail.set(""); var retry = grading.grade(answers(3));
        assertEquals(prefix.equals("submission-") ? 10.0 : 3.33, retry.result().diemSo()); proof.put("fileFailure-" + prefix, true);
    }
    @Test void promotionFailureAfterCommitReconcilesOnRestartWithoutRescoring() throws Exception {
        fail.set("publish-grade"); assertEquals(503, assertThrows(AuthException.class, () -> grading.grade(answers(1))).status());
        assertDb(3.33, TrangThaiBaiLam.HOAN_THANH); var original = store.readFirst(code, student.idHs()).orElseThrow();
        store.close(); fail.set(""); open(); var retry = grading.grade(answers(3));
        assertEquals(3.33, retry.result().diemSo()); assertEquals(original.submissionId(), retry.receipt().submissionId());
        assertEquals(TrangThaiBaiLam.HOAN_THANH, store.readRecovery(code, student.idHs()).orElseThrow().trangThai());
        proof.put("postCommitPublishFailureReconciledOnRestart", true);
    }
    @Test void cleanRestartAndMissingOrCorruptGradeNeverRecalculate() throws Exception {
        var first = grading.grade(answers(1)); store.close(); open(); assertEquals(first.result(), grading.grade(answers(3)).result());
        Path grade = file("grade-"); String saved = Files.readString(grade); Files.writeString(grade, "{broken");
        assertEquals(503, assertThrows(AuthException.class, () -> grading.grade(answers(3))).status());
        Files.delete(grade); assertEquals(503, assertThrows(AuthException.class, () -> grading.grade(answers(3))).status()); assertDb(3.33, TrangThaiBaiLam.HOAN_THANH);
        Files.writeString(grade, saved); Files.delete(file("submission-"));
        assertEquals(503, assertThrows(AuthException.class, () -> grading.grade(answers(3))).status()); assertDb(3.33, TrangThaiBaiLam.HOAN_THANH);
        proof.put("restartAndMissingMetadataNoRescore", true);
    }
    @Test void invalidDatabaseAnswerKeyFailsWithoutAnySubmission() throws Exception {
        tx.execute(c -> { try (var s = c.prepareStatement("UPDATE cau_hoi SET dap_an_dung='X' WHERE id_cau_hoi=?")) { s.setString(1, run.cauHoiList().get(0).idCauHoi()); s.executeUpdate(); } return null; });
        assertEquals(503, assertThrows(AuthException.class, () -> grading.grade(answers(1))).status());
        assertTrue(store.readFirst(code, student.idHs()).isEmpty()); assertDb(0.0, TrangThaiBaiLam.DANG_THI);
    }
    @Test void changedQuestionSetAndEmptyExamAreRejected() throws Exception {
        tx.execute(c -> { try (var s = c.prepareStatement("DELETE FROM cau_hoi WHERE id_cau_hoi=?")) { s.setString(1, run.cauHoiList().get(0).idCauHoi()); s.executeUpdate(); } return null; });
        assertEquals(409, assertThrows(AuthException.class, () -> grading.grade(answers(1))).status());
        tx.execute(c -> { try (var s = c.prepareStatement("DELETE FROM cau_hoi WHERE id_de_thi=?")) { s.setString(1, run.idDeThi()); s.executeUpdate(); } return null; });
        assertEquals(409, assertThrows(AuthException.class, () -> grading.grade(answers(0))).status()); assertTrue(store.readFirst(code, student.idHs()).isEmpty());
    }
    @Test void lateTimeoutAcceptedAndReceiptNotMovedByRetry() throws Exception {
        clock.time.set(run.endsAt() + 5000); var first = grading.grade(answers(1), run.runId(), "timeout");
        assertTrue(first.receipt().receivedLate()); assertEquals("timeout", first.receipt().reason()); assertEquals(3.33, first.result().diemSo());
        clock.time.addAndGet(5000); assertEquals(first.receipt().receivedAt(), grading.grade(answers(3)).receipt().receivedAt()); proof.put("lateAcceptedFlagPreserved", true);
    }
    @Test void closedRoomRejectsNewButGradesPreviouslyReceivedSnapshot() throws Exception {
        closed(); assertEquals(409, assertThrows(AuthException.class, () -> grading.grade(answers(1))).status()); assertTrue(store.readFirst(code, student.idHs()).isEmpty());
        seedExam(3); var snapshot = new Submission("legacy-first", run.runId(), student.idHs(), clock.millis(), false, "manual", false, answers(1).selectedAnswers());
        store.receiveFirst(code, snapshot); closed(); var graded = grading.grade(answers(3));
        assertEquals(3.33, graded.result().diemSo()); assertEquals("legacy-first", graded.receipt().submissionId()); assertTrue(graded.receipt().duplicate());
        assertEquals(graded.result(), grading.grade(answers(0)).result()); proof.put("closedOnlyExistingSnapshot", true);
    }
    @Test void completedResultCannotBeDowngradedByRecoveryAndGradingDisconnectedIsValid() throws Exception {
        flow.recoveryService().markDisconnected(student, clock.millis()); assertDb(0.0, TrangThaiBaiLam.MAT_KET_NOI);
        var grade = grading.grade(answers(1)); assertDb(3.33, TrangThaiBaiLam.HOAN_THANH);
        assertNull(flow.recoveryService().markDisconnected(student, clock.millis()));
        var sync = flow.recoveryService().reconnect(student, null, clock.millis()); assertEquals(TrangThaiBaiLam.HOAN_THANH, sync.studentState().trangThai());
        assertEquals(grade.receipt().submissionId(), sync.submission().submissionId()); proof.put("completedNoRecoveryDowngrade", true);
    }
    @Test void actualRowLockBlocksGradingUntilCompetingTransactionFinishes() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        try (var c = pool.getConnection()) {
            c.setAutoCommit(false); new RoomDAO().lockByCode(c, code).orElseThrow(); new StudentDAO().lockInRoom(c, student.idHs(), student.idPhongThi()).orElseThrow();
            new SubmissionDAO().lockInRoom(c, student.idHs(), student.idPhongThi()).orElseThrow();
            var pending = workers.submit(() -> grading.grade(answers(1))); Thread.sleep(200); assertFalse(pending.isDone()); c.commit();
            assertEquals(3.33, pending.get(8, TimeUnit.SECONDS).result().diemSo()); assertDb(3.33, TrangThaiBaiLam.HOAN_THANH);
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
        proof.put("actualRoomStudentResultRowLocks", true);
    }
}
