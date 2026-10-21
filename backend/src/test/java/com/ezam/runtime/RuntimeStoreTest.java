package com.ezam.runtime;

import com.ezam.model.TrangThaiBaiLam;
import com.ezam.model.TrangThaiPhongThi;
import com.ezam.model.runtime.RuntimeData.*;
import com.ezam.util.runtime.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real filesystem, synthetic DB evidence. PostgreSQL evidence is tested separately in PostgresRuntimeIT. */
class RuntimeStoreTest {
    @TempDir Path directory;
    private final AtomicReference<DbState> room = new AtomicReference<>(state(null, TrangThaiPhongThi.DANG_CHO, null, null));
    private final AtomicReference<DbState> student = new AtomicReference<>(state("legacy-student", TrangThaiPhongThi.DANG_CHO, TrangThaiBaiLam.DANG_THI, 0.0));

    static DbState state(String student, TrangThaiPhongThi room, TrangThaiBaiLam result, Double score) {
        return new DbState("legacy-room", "AB12CD", "legacy-exam", 1, room, student, student == null ? null : "result-id", result, score);
    }
    static Run run() { return new Run("legacy-run", "legacy-room", "legacy-exam", "AB12CD", "Đề mẫu", "Lớp 6A", 1,
            "DANG_THI", 1000, 61000, 1000, List.of(new Question("legacy-question", "Nước là gì?", "H2O", "CO2", "O2", "NaCl"))); }
    static Submission submission(String id, String answer) {
        return new Submission(id, "legacy-run", "legacy-student", 2000, false, "manual", false, Map.of("legacy-question", answer));
    }
    private DbState evidence(String code, String id) { return id == null ? room.get() : student.get(); }
    private RuntimeStore store() { return new RuntimeStore(directory, this::evidence); }
    private void active() {
        room.set(state(null, TrangThaiPhongThi.DANG_THI, null, null));
        student.set(state("legacy-student", TrangThaiPhongThi.DANG_THI, TrangThaiBaiLam.DANG_THI, 0.0));
    }
    private void legacyFiles() throws Exception {
        try (var manifest = RuntimeStoreTest.class.getResourceAsStream("/runtime/legacy-provenance.json")) { assertNotNull(manifest); }
        for (String file : List.of("run-AB12CD.json", "grade-9d2e1e56c9af3ab6ed4c54ea3133614163d3ad85cc2aed623d335db3d4979a62.json",
                "recovery-9d2e1e56c9af3ab6ed4c54ea3133614163d3ad85cc2aed623d335db3d4979a62.json",
                "submission-fe715b1bdf5f89c31441ab4ed4c1ac2900c5fd1c4c08bd1cd75ee0b6105c7a18.json")) {
            try (var in = RuntimeStoreTest.class.getResourceAsStream("/runtime/legacy/" + file)) {
                assertNotNull(in); Files.copy(in, directory.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    private static String hash(String a, String b) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((a + ":" + b).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void readsAllFourActualLegacyFormatsAndKeepsDeadline() throws Exception {
        legacyFiles(); active(); student.set(state("legacy-student", TrangThaiPhongThi.DANG_THI, TrangThaiBaiLam.HOAN_THANH, 10.0));
        try (RuntimeStore store = store()) {
            assertEquals(61000, store.readRun("AB12CD").orElseThrow().endsAt());
            assertEquals("legacy-submission", store.readFirst("AB12CD", "legacy-student").orElseThrow().submissionId());
            assertEquals(2, store.readRecovery("AB12CD", "legacy-student").orElseThrow().stateVersion());
            assertEquals(10.0, store.readGrade("AB12CD", "legacy-student").orElseThrow().result().diemSo());
        }
    }

    @Test void preparesThenPublishesOnlyAfterDbEvidenceChanges() throws Exception {
        try (RuntimeStore store = store()) {
            store.exclusive(unit -> {
                unit.bindBefore(room.get());
                unit.prepareRun(room.get(), state(null, TrangThaiPhongThi.DANG_THI, null, null), run());
                assertFalse(Files.exists(directory.resolve("run-AB12CD.json")));
                assertThrows(IllegalStateException.class, () -> store.readRun("AB12CD"));
                active(); unit.finishCommitted(); return null;
            });
            assertEquals(run(), store.readRun("AB12CD").orElseThrow());
        }
    }

    @Test void interruptedBeforeCommitNeverServesPreparedRunAfterRestart() throws Exception {
        try (RuntimeStore store = store()) {
            assertThrows(java.sql.SQLException.class, () -> store.exclusive(unit -> {
                unit.bindBefore(room.get());
                unit.prepareRun(room.get(), state(null, TrangThaiPhongThi.DANG_THI, null, null), run());
                throw new java.sql.SQLException("Synthetic commit failure");
            }));
        }
        try (RuntimeStore reopened = store()) {
            assertTrue(reopened.readRun("AB12CD").isEmpty());
            assertFalse(Files.exists(directory.resolve("run-AB12CD.json.pending")));
        }
    }

    @Test void commitSucceededButPromotionFailedReconcilesOnRestart() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        try (RuntimeStore store = new RuntimeStore(directory, this::evidence, (step, path) -> {
            if (step.equals("before-move") && path.getFileName().toString().equals("run-AB12CD.json") && fail.getAndSet(false)) throw new java.io.IOException();
        })) {
            assertThrows(RuntimeUnavailableException.class, () -> store.exclusive(unit -> {
                unit.bindBefore(room.get());
                unit.prepareRun(room.get(), state(null, TrangThaiPhongThi.DANG_THI, null, null), run());
                active(); unit.finishCommitted(); return null;
            }));
        }
        try (RuntimeStore reopened = store()) {
            assertEquals(61000, reopened.readRun("AB12CD").orElseThrow().endsAt());
            assertFalse(Files.exists(directory.resolve("run-AB12CD.json.pending")));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"before-write", "before-move"})
    void fileFailureLeavesNoPartialRunOrTemporaryFile(String failStep) throws Exception {
        try (RuntimeStore store = new RuntimeStore(directory, this::evidence, (step, target) -> { if (step.equals(failStep)) throw new java.io.IOException(); })) {
            assertThrows(RuntimeUnavailableException.class, () -> store.exclusive(unit -> {
                unit.bindBefore(room.get());
                unit.prepareRun(room.get(), state(null, TrangThaiPhongThi.DANG_THI, null, null), run()); return null;
            }));
        }
        try (var files = Files.list(directory)) { assertEquals(List.of(".java-runtime.lock"), files.map(p -> p.getFileName().toString()).toList()); }
    }

    @Test void firstAnswersAndReceiptRemainImmutableAcrossRetryAndRestart() throws Exception {
        active(); Files.writeString(directory.resolve("run-AB12CD.json"), new com.google.gson.Gson().toJson(run()));
        Receipt first;
        try (RuntimeStore store = store()) {
            first = store.receiveFirst("AB12CD", submission("first", "A")); assertFalse(first.duplicate());
            Receipt duplicate = store.receiveFirst("AB12CD", submission("second", "B"));
            assertTrue(duplicate.duplicate()); assertEquals(first.submissionId(), duplicate.submissionId());
        }
        try (RuntimeStore store = store()) {
            assertEquals("A", store.readFirst("AB12CD", "legacy-student").orElseThrow().answers().get("legacy-question"));
            assertEquals("first", store.receiveFirst("AB12CD", submission("third", "C")).submissionId());
        }
    }

    @Test void twoThreadsCannotReplaceFirstSnapshot() throws Exception {
        active(); Files.writeString(directory.resolve("run-AB12CD.json"), new com.google.gson.Gson().toJson(run()));
        var workers = Executors.newFixedThreadPool(2);
        try (RuntimeStore store = store()) {
            var results = workers.invokeAll(List.of(() -> store.receiveFirst("AB12CD", submission("one", "A")),
                    () -> store.receiveFirst("AB12CD", submission("two", "B"))));
            Receipt a = (Receipt) results.get(0).get(); Receipt b = (Receipt) results.get(1).get();
            assertEquals(a.submissionId(), b.submissionId()); assertNotEquals(a.duplicate(), b.duplicate());
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void completedGradeAndRecoveryCanBePreparedTogether() throws Exception {
        active(); Files.writeString(directory.resolve("run-AB12CD.json"), new com.google.gson.Gson().toJson(run()));
        try (RuntimeStore store = store()) {
            store.receiveFirst("AB12CD", submission("first", "A"));
            DbState done = state("legacy-student", TrangThaiPhongThi.DANG_THI, TrangThaiBaiLam.HOAN_THANH, 10.0);
            store.exclusive(unit -> {
                unit.bindBefore(student.get());
                unit.prepareRecovery(student.get(), done, new Recovery("legacy-student", "Nguyễn An", "AB12CD", "legacy-room", TrangThaiBaiLam.HOAN_THANH, 1, 3000, null, null));
                unit.prepareGrade(student.get(), done, new StoredGrade("legacy-room", "legacy-run", new Grade("legacy-student", 10, 1, 1, TrangThaiBaiLam.HOAN_THANH)));
                student.set(done); unit.finishCommitted(); return null;
            });
            assertEquals(1, store.readGrade("AB12CD", "legacy-student").orElseThrow().result().soCauDung());
            assertEquals(TrangThaiBaiLam.HOAN_THANH, store.readRecovery("AB12CD", "legacy-student").orElseThrow().trangThai());
        }
        student.set(state("legacy-student", TrangThaiPhongThi.DA_DONG, TrangThaiBaiLam.HOAN_THANH, 10.0));
        try (RuntimeStore store = store()) { assertEquals(10, store.readGrade("AB12CD", "legacy-student").orElseThrow().result().diemSo()); }
    }

    @Test void missingOrMismatchingGradeDoesNotRecalculateFromScore() throws Exception {
        active(); student.set(state("legacy-student", TrangThaiPhongThi.DANG_THI, TrangThaiBaiLam.HOAN_THANH, 10.0));
        try (RuntimeStore store = store()) { assertEquals(503, assertThrows(RuntimeUnavailableException.class, () -> store.readGrade("AB12CD", "legacy-student")).getStatusCode()); }
        legacyFiles(); student.set(state("legacy-student", TrangThaiPhongThi.DANG_THI, TrangThaiBaiLam.HOAN_THANH, 9.0));
        try (RuntimeStore store = store()) { assertThrows(RuntimeUnavailableException.class, () -> store.readGrade("AB12CD", "legacy-student")); }
    }

    @Test void missingDeadlineFailsClosedInsteadOfCreatingAnotherRun() {
        active(); try (RuntimeStore store = store()) { assertEquals(503, assertThrows(RuntimeUnavailableException.class, () -> store.readRun("AB12CD")).getStatusCode()); }
    }

    @ParameterizedTest @ValueSource(strings = {"{", "null", "[]", "{\"runId\":\"one\",\"runId\":\"two\"}", "/* comment */{}"})
    void corruptJsonIsNeverTreatedAsAnEmptyRun(String invalid) throws Exception {
        active(); Files.writeString(directory.resolve("run-AB12CD.json"), invalid);
        try (RuntimeStore store = store()) { assertThrows(RuntimeUnavailableException.class, () -> store.readRun("AB12CD")); }
    }

    @ParameterizedTest @ValueSource(strings = {"extra", "answer", "numericString", "fraction", "integralFloat", "exponent", "unknownState", "wrongRoom", "wrongDeadline"})
    void rejectsShapeTypeAndIdentityViolations(String mutation) throws Exception {
        active(); var json = com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(run())).getAsJsonObject();
        switch (mutation) {
            case "extra" -> json.addProperty("secret", "unexpected");
            case "answer" -> json.getAsJsonArray("cauHoiList").get(0).getAsJsonObject().addProperty("dapAnDung", "A");
            case "numericString" -> json.addProperty("startsAt", "1000");
            case "fraction" -> json.addProperty("startsAt", 1000.5);
            case "integralFloat" -> json.addProperty("startsAt", 1000.0);
            case "exponent" -> json.add("startsAt", com.google.gson.JsonParser.parseString("1e3"));
            case "unknownState" -> json.addProperty("trangThaiPhong", "STARTED");
            case "wrongRoom" -> json.addProperty("idPhongThi", "different-room");
            case "wrongDeadline" -> json.addProperty("endsAt", 62000);
            default -> throw new AssertionError();
        }
        Files.writeString(directory.resolve("run-AB12CD.json"), json.toString());
        try (RuntimeStore store = store()) { assertThrows(RuntimeUnavailableException.class, () -> store.readRun("AB12CD")); }
    }

    @Test void malformedUtf8IsRejected() throws Exception {
        active(); Files.write(directory.resolve("run-AB12CD.json"), new byte[] {(byte) 0xc3, (byte) 0x28});
        try (RuntimeStore store = store()) { assertThrows(RuntimeUnavailableException.class, () -> store.readRun("AB12CD")); }
    }

    @Test void ignoresUncommittedLegacyGradeAndRecoveryNeverOverridesDb() throws Exception {
        legacyFiles(); active();
        try (RuntimeStore store = store()) {
            assertTrue(store.readGrade("AB12CD", "legacy-student").isEmpty());
            assertThrows(RuntimeUnavailableException.class, () -> store.readRecovery("AB12CD", "legacy-student"));
        }
    }

    @Test void failedRecoveryCommitKeepsPreviousVersionAndFreeze() throws Exception {
        active();
        try (RuntimeStore store = store()) {
            DbState disconnected = state("legacy-student", TrangThaiPhongThi.DANG_THI, TrangThaiBaiLam.MAT_KET_NOI, 0.0);
            store.exclusive(unit -> {
                unit.bindBefore(student.get());
                unit.prepareRecovery(student.get(), disconnected, new Recovery("legacy-student", "Nguyễn An", "AB12CD", "legacy-room", TrangThaiBaiLam.MAT_KET_NOI, 1, 11000, 11000L, 50L));
                student.set(disconnected); unit.finishCommitted(); return null;
            });
            DbState recovered = state("legacy-student", TrangThaiPhongThi.DANG_THI, TrangThaiBaiLam.DANG_THI, 0.0);
            assertThrows(java.sql.SQLException.class, () -> store.exclusive(unit -> {
                unit.bindBefore(student.get());
                unit.prepareRecovery(student.get(), recovered, new Recovery("legacy-student", "Nguyễn An", "AB12CD", "legacy-room", TrangThaiBaiLam.DANG_THI, 2, 26000, 11000L, 50L));
                throw new java.sql.SQLException();
            }));
        }
        try (RuntimeStore store = store()) {
            Recovery old = store.readRecovery("AB12CD", "legacy-student").orElseThrow();
            assertEquals(1, old.stateVersion()); assertEquals(50L, old.frozenRemainingSeconds());
        }
    }

    @Test void singleDirectoryOwnerAndExpiredUnitAreEnforced() throws Exception {
        RuntimeStore.Unit[] retained = new RuntimeStore.Unit[1];
        try (RuntimeStore store = store()) {
            assertThrows(RuntimeUnavailableException.class, this::store);
            store.exclusive(unit -> { retained[0] = unit; return null; });
            assertThrows(IllegalStateException.class, () -> retained[0].finishCommitted());
        }
        try (RuntimeStore reopened = store()) { assertTrue(reopened.readRun("AB12CD").isEmpty()); }
    }

    @ParameterizedTest @ValueSource(strings = {"../../outside", "AB12CD/../../outside", "ab12cd", "", "CON"})
    void invalidCodesCannotAddressFilesystem(String code) {
        try (RuntimeStore store = store()) { assertThrows(RuntimeUnavailableException.class, () -> store.readRun(code)); }
    }

    @Test void hashedStudentIdsCannotTraverseAndCrossIdentityFileFails() throws Exception {
        legacyFiles(); active();
        String weird = "../legacy-student";
        student.set(new DbState("legacy-room", "AB12CD", "legacy-exam", 1, TrangThaiPhongThi.DANG_THI, weird, "result-id", TrangThaiBaiLam.DANG_THI, 0.0));
        Files.copy(directory.resolve("submission-" + hash("legacy-run", "legacy-student") + ".json"), directory.resolve("submission-" + hash("legacy-run", weird) + ".json"));
        try (RuntimeStore store = store()) { assertThrows(RuntimeUnavailableException.class, () -> store.readFirst("AB12CD", weird)); }
    }

    @Test void explicitRuntimeConfigurationIsRequiredAndInvalidPathsAreSafe() {
        assertEquals(503, assertThrows(RuntimeUnavailableException.class, () -> RuntimeStore.fromEnvironment(Map.of(), this::evidence)).getStatusCode());
        assertThrows(RuntimeUnavailableException.class, () -> RuntimeStore.fromEnvironment(Map.of("RUNTIME_DIRECTORY", "bad\0path"), this::evidence));
        try (RuntimeStore store = RuntimeStore.fromEnvironment(Map.of("RUNTIME_DIRECTORY", directory.toString()), this::evidence)) {
            assertTrue(store.readRun("AB12CD").isEmpty());
        }
    }

    @Test void unchangedDbProofCannotPublishPreparedMetadata() throws Exception {
        try (RuntimeStore store = store()) {
            assertThrows(RuntimeUnavailableException.class, () -> store.exclusive(unit -> {
                unit.bindBefore(room.get());
                unit.prepareRun(room.get(), room.get(), run()); return null;
            }));
            assertTrue(store.readRun("AB12CD").isEmpty());
        }
    }
}
