package com.ezam.util.runtime;

import com.ezam.model.TrangThaiBaiLam;
import com.ezam.model.TrangThaiPhongThi;
import com.ezam.model.runtime.RuntimeData.*;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Single JVM, single owner per directory. Sidecars + PostgreSQL are NOT a distributed transaction. */
public final class RuntimeStore implements AutoCloseable {
    @FunctionalInterface public interface Evidence { DbState committed(String code, String studentId); }
    @FunctionalInterface public interface Work<T> { T run(Unit unit) throws Exception; }
    @FunctionalInterface public interface IoFault { void at(String step, Path target) throws IOException; }
    private record Prepared(int formatVersion, Kind kind, DbState before, DbState after, JsonElement payload) { }

    private final Path directory;
    private final Evidence evidence;
    private final IoFault fault;
    private final FileChannel lease;
    private final FileLock lock;
    private Thread writer;
    private boolean closed;

    public static RuntimeStore fromEnvironment(Evidence evidence) {
        return fromEnvironment(System.getenv(), evidence);
    }
    public static RuntimeStore fromEnvironment(Map<String, String> environment, Evidence evidence) {
        try {
            String value = environment.get("RUNTIME_DIRECTORY");
            if (value == null || value.isBlank()) throw new IllegalArgumentException();
            return new RuntimeStore(Path.of(value), evidence);
        } catch (Exception invalid) { throw new RuntimeUnavailableException(); }
    }

    public RuntimeStore(Path directory, Evidence evidence) { this(directory, evidence, (step, path) -> { }); }

    /** Fault seam for testing filesystem failure paths; production uses the constructor above. */
    public RuntimeStore(Path requested, Evidence evidence, IoFault fault) {
        FileChannel channel = null;
        try {
            this.directory = requested.toAbsolutePath().normalize(); rejectLinks(this.directory);
            boolean existed = Files.exists(this.directory, LinkOption.NOFOLLOW_LINKS);
            Files.createDirectories(this.directory); rejectLinks(this.directory);
            if (!existed && Files.getFileStore(this.directory).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
            }
            this.evidence = Objects.requireNonNull(evidence); this.fault = Objects.requireNonNull(fault);
            Path owner = safe(".java-runtime.lock");
            channel = FileChannel.open(owner, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock acquired = channel.tryLock();
            if (acquired == null) throw new IOException();
            this.lease = channel; this.lock = acquired;
        } catch (Exception invalid) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) { /* constructor failure */ }
            throw new RuntimeUnavailableException();
        }
    }

    /** All writers/readers coordinate through this owner; never enter holding a DB row lock. */
    public synchronized <T> T exclusive(Work<T> work) throws Exception {
        open(); if (writer != null) throw new IllegalStateException("Nested runtime units are unsupported.");
        writer = Thread.currentThread(); Unit unit = new Unit();
        try { return work.run(unit); }
        finally { unit.active = false; writer = null; }
    }

    public final class Unit {
        private boolean active = true;
        private DbState boundBefore;
        private final List<Path> prepared = new ArrayList<>();
        private Unit() { }
        private void check() {
            open(); if (!active || writer != Thread.currentThread() || !Thread.holdsLock(RuntimeStore.this)) throw new IllegalStateException("Inactive runtime unit.");
        }

        /** Coordinator binds a fresh snapshot from its locked JDBC connection before running user work. */
        public void bindBefore(DbState state) {
            check(); if (boundBefore != null) throw new IllegalStateException("Runtime unit is already bound.");
            boundBefore = Objects.requireNonNull(state);
        }

        public void prepareRun(DbState before, DbState after, Run run) { check(); stage(Kind.RUN, before, after, run); }
        public void prepareRecovery(DbState before, DbState after, Recovery recovery) { check(); stage(Kind.RECOVERY, before, after, recovery); }
        public void prepareGrade(DbState before, DbState after, StoredGrade grade) { check(); stage(Kind.GRADE, before, after, grade); }

        private void stage(Kind kind, DbState before, DbState after, Object payload) {
            try {
                if (!before.equals(boundBefore) || !before.sameContext(after) || before.matches(kind, after)) throw new IllegalArgumentException();
                validate(kind, payload, after);
                if (kind == Kind.RUN && (before.roomState() != TrangThaiPhongThi.DANG_CHO || after.roomState() != TrangThaiPhongThi.DANG_THI)) throw new IllegalArgumentException();
                if (kind != Kind.RUN && before.resultState() == TrangThaiBaiLam.HOAN_THANH) throw new IllegalArgumentException();
                Path path = path(kind, after);
                resolve(path, before);
                if (kind == Kind.RUN && Files.exists(path)) {
                    // A legacy prepared run in DANG_CHO is not proof of a committed deadline.
                    // It may be replaced only while PostgreSQL is still DANG_CHO.
                    if (before.roomState() != TrangThaiPhongThi.DANG_CHO) throw new IllegalArgumentException();
                }
                if (kind == Kind.GRADE && Files.exists(path)) throw new IllegalArgumentException();
                if (kind == Kind.RECOVERY && Files.exists(path)) {
                    Recovery prior = read(path, Recovery.class); Recovery next = (Recovery) payload;
                    if (next.stateVersion() <= prior.stateVersion() || next.stateChangedAt() < prior.stateChangedAt()) throw new IllegalArgumentException();
                }
                Path journal = pending(path);
                write(journal, new Prepared(1, kind, before, after, RuntimeJson.tree(payload)));
                prepared.add(journal);
            } catch (Exception unavailable) { throw new RuntimeUnavailableException(); }
        }

        /** Only after the Service's JDBC commit/close; fresh evidence resolves interrupted promotions too. */
        public void finishCommitted() {
            check();
            for (Path journal : prepared) {
                Prepared candidate = read(journal, Prepared.class);
                DbState state = fresh(candidate.after().maCodePhongThi(), candidate.after().idHs());
                if (!candidate.after().matches(candidate.kind(), state)) throw new RuntimeUnavailableException();
                resolve(target(journal), state);
            }
        }
    }

    public synchronized Optional<Run> readRun(String code) {
        readable(); DbState state = fresh(code, null); Path path = path(Kind.RUN, state); resolve(path, state);
        if (state.roomState() != TrangThaiPhongThi.DANG_THI) return Optional.empty();
        Run run = requiredRun(state); return Optional.of(run);
    }

    /** Private grading path: a closed room may finish an already received snapshot, never a new run. */
    public synchronized Run readSubmissionRun(String code, String studentId) {
        readable(); return requiredRun(fresh(code, studentId));
    }

    public synchronized Optional<Recovery> readRecovery(String code, String studentId) {
        readable(); DbState state = fresh(code, studentId); Path path = path(Kind.RECOVERY, state); resolve(path, state);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        Recovery recovery = read(path, Recovery.class); validate(Kind.RECOVERY, recovery, state); return Optional.of(recovery);
    }

    public synchronized Optional<StoredGrade> readGrade(String code, String studentId) {
        readable(); DbState state = fresh(code, studentId); Path path = path(Kind.GRADE, state); resolve(path, state);
        if (state.resultState() != TrangThaiBaiLam.HOAN_THANH) return Optional.empty();
        StoredGrade grade = read(path, StoredGrade.class); validate(Kind.GRADE, grade, state); return Optional.of(grade);
    }

    /** Durable private first snapshot. Returning a receipt/Grade publicly requires the DB-completed guard. */
    public synchronized Receipt receiveFirst(String code, Submission submission) {
        readable(); DbState state = fresh(code, submission.idHs()); Run run = requiredRun(state);
        validateSubmission(submission, run, state.idHs());
        Path path = submissionPath(submission.runId(), submission.idHs());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Submission first = read(path, Submission.class); validateSubmission(first, run, state.idHs()); return first.receipt(true);
        }
        if (state.roomState() != TrangThaiPhongThi.DANG_THI || state.resultState() == TrangThaiBaiLam.HOAN_THANH || submission.duplicate()) throw new RuntimeUnavailableException();
        write(path, submission); return submission.receipt(false);
    }

    /** Grading may inspect the first private snapshot before HOAN_THANH; never send answers to a client. */
    public synchronized Optional<Submission> readFirst(String code, String studentId) {
        readable(); DbState state = fresh(code, studentId); Run run = requiredRun(state);
        Path path = submissionPath(run.runId(), studentId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        Submission submission = read(path, Submission.class); validateSubmission(submission, run, studentId); return Optional.of(submission);
    }

    private DbState fresh(String code, String studentId) {
        try {
            com.ezam.model.runtime.RuntimeData.code(code);
            DbState state = evidence.committed(code, studentId);
            if (state == null || !code.equals(state.maCodePhongThi()) || !Objects.equals(studentId, state.idHs())) throw new IllegalArgumentException();
            return state;
        } catch (Exception unavailable) { throw new RuntimeUnavailableException(); }
    }

    private Run requiredRun(DbState state) {
        if (state.roomState() == TrangThaiPhongThi.DANG_CHO) throw new RuntimeUnavailableException();
        DbState roomOnly = new DbState(state.idPhongThi(), state.maCodePhongThi(), state.idDeThi(), state.thoiGianLamBai(), state.roomState(), null, null, null, null);
        Path path = path(Kind.RUN, roomOnly);
        // Do not promote a new run using a caller's uncommitted after-state during grade validation.
        if (writer != null && Files.exists(pending(path), LinkOption.NOFOLLOW_LINKS)) throw new RuntimeUnavailableException();
        resolve(path, roomOnly);
        Run run = read(path, Run.class); validate(Kind.RUN, run, roomOnly); return run;
    }

    private void validate(Kind kind, Object payload, DbState state) {
        try {
            switch (kind) {
                case RUN -> {
                    Run run = (Run) payload;
                    if (!run.idPhongThi().equals(state.idPhongThi()) || !run.maCodePhongThi().equals(state.maCodePhongThi())
                            || !run.idDeThi().equals(state.idDeThi()) || run.thoiGianLamBai() != state.thoiGianLamBai()) throw new IllegalArgumentException();
                }
                case RECOVERY -> {
                    Recovery recovery = (Recovery) payload;
                    if (!recovery.idPhongThi().equals(state.idPhongThi()) || !recovery.idHs().equals(state.idHs())
                            || !recovery.maCodePhongThi().equals(state.maCodePhongThi()) || recovery.trangThai() != state.resultState()) throw new IllegalArgumentException();
                }
                case GRADE -> {
                    StoredGrade grade = (StoredGrade) payload;
                    if (!grade.idPhongThi().equals(state.idPhongThi()) || !grade.result().idHs().equals(state.idHs())
                            || state.resultState() != TrangThaiBaiLam.HOAN_THANH || Double.compare(grade.result().diemSo(), state.diemSo()) != 0) throw new IllegalArgumentException();
                    Run run = requiredRun(state);
                    if (!grade.runId().equals(run.runId()) || grade.result().tongSoCau() != run.cauHoiList().size()) throw new IllegalArgumentException();
                    Submission submission = read(submissionPath(run.runId(), state.idHs()), Submission.class);
                    validateSubmission(submission, run, state.idHs());
                }
            }
        } catch (Exception invalid) { throw new RuntimeUnavailableException(); }
    }

    private void validateSubmission(Submission value, Run run, String studentId) {
        if (!value.runId().equals(run.runId()) || !value.idHs().equals(studentId) || value.receivedLate() != (value.receivedAt() > run.endsAt())
                || !new HashSet<>(run.cauHoiList().stream().map(Question::idCauHoi).toList()).containsAll(value.answers().keySet())) throw new RuntimeUnavailableException();
    }

    private void resolve(Path path, DbState actual) {
        Path journal = pending(path);
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) return;
        Prepared p = read(journal, Prepared.class);
        if (p.formatVersion() != 1 || !path.equals(path(p.kind(), p.after())) || !p.before().sameContext(p.after()) || p.before().matches(p.kind(), p.after())) throw new RuntimeUnavailableException();
        Object payload = switch (p.kind()) {
            case RUN -> RuntimeJson.read(p.payload(), Run.class);
            case RECOVERY -> RuntimeJson.read(p.payload(), Recovery.class);
            case GRADE -> RuntimeJson.read(p.payload(), StoredGrade.class);
        };
        try {
            if (p.after().matches(p.kind(), actual)) {
                validate(p.kind(), payload, actual); write(path, payload); Files.delete(journal);
            } else if (p.before().matches(p.kind(), actual)) {
                Files.delete(journal); // DB rolled back: keep the preceding committed sidecar.
            } else throw new RuntimeUnavailableException();
        } catch (Exception unavailable) { throw new RuntimeUnavailableException(); }
    }

    private Path path(Kind kind, DbState state) {
        return switch (kind) {
            case RUN -> safe("run-" + state.maCodePhongThi() + ".json");
            case RECOVERY -> safe("recovery-" + digest(state.idPhongThi(), Objects.requireNonNull(state.idHs())) + ".json");
            case GRADE -> safe("grade-" + digest(state.idPhongThi(), Objects.requireNonNull(state.idHs())) + ".json");
        };
    }
    private Path submissionPath(String run, String student) { return safe("submission-" + digest(run, student) + ".json"); }
    private Path pending(Path target) { return safe(target.getFileName() + ".pending"); }
    private Path target(Path pending) { String name = pending.getFileName().toString(); return safe(name.substring(0, name.length() - ".pending".length())); }
    private static String digest(String a, String b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((a + ":" + b).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new RuntimeUnavailableException(); }
    }

    private Path safe(String filename) {
        Path result = directory.resolve(filename).normalize();
        if (!result.getParent().equals(directory)) throw new RuntimeUnavailableException();
        try { rejectLinks(result); } catch (IOException invalid) { throw new RuntimeUnavailableException(); }
        return result;
    }
    private static void rejectLinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current) || Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()) throw new IOException();
        }
    }
    private <T> T read(Path path, Class<T> type) {
        try {
            safe(path.getFileName().toString());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16 * 1024 * 1024) throw new IOException();
            return RuntimeJson.read(Files.readString(path, StandardCharsets.UTF_8), type);
        } catch (Exception unavailable) { throw new RuntimeUnavailableException(); }
    }
    private void write(Path path, Object value) {
        Path temporary = null;
        try {
            safe(path.getFileName().toString()); fault.at("before-write", path);
            temporary = Files.createTempFile(directory, ".runtime-", ".tmp");
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            byte[] bytes = RuntimeJson.write(value).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 16 * 1024 * 1024) throw new IOException();
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            fault.at("before-move", path);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception unavailable) { throw new RuntimeUnavailableException(); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* orphan temp is never read */ } }
    }
    private void open() { if (closed) throw new RuntimeUnavailableException(); }
    private void readable() { open(); if (writer != null) throw new IllegalStateException("Read runtime only outside its JDBC write unit."); }
    @Override public synchronized void close() {
        if (closed) return;
        if (writer != null) throw new IllegalStateException("Cannot close an active runtime unit.");
        closed = true;
        try { try { lock.release(); } finally { lease.close(); } } catch (IOException unavailable) { throw new RuntimeUnavailableException(); }
    }
}
