package com.ezam.service;

import com.ezam.dao.*;
import com.ezam.dto.SubmissionDTO.*;
import com.ezam.model.*;
import com.ezam.model.runtime.RuntimeData.*;
import com.ezam.util.AuthException;
import com.ezam.util.DtoValidator;
import com.ezam.util.runtime.RuntimeStore;
import java.math.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;

/** One grading implementation for future HTTP/Socket adapters; no transport side effects here. */
public final class GradingService {
    private final TransactionManager transactions;
    private final RuntimeStore store;
    private final RuntimeTransactionService writes;
    private final Clock clock;
    private final RoomDAO rooms = new RoomDAO();
    private final StudentDAO students = new StudentDAO();
    private final SubmissionDAO submissions = new SubmissionDAO();
    private final ExamDAO exams = new ExamDAO();
    private record Target(PhongThi room, HocSinh student, KetQuaThi result) { }

    public GradingService(TransactionManager transactions, RuntimeStore store, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions); this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock); this.writes = new RuntimeTransactionService(store, transactions);
    }
    public Outcome grade(Request request) { return grade(request, null, "manual"); }

    /** Read-only Grade lookup, including closed rooms. Never reconstruct counts or regrade. */
    public Grade result(String studentId, String roomId) {
        var request = new Request(studentId, roomId, List.of());
        synchronized (store) {
            try {
                Target target = transactions.execute(c -> target(c, request));
                if (target.result().getTrangThai() != TrangThaiBaiLam.HOAN_THANH)
                    throw new AuthException(409, "The submission has not been graded yet.");
                return store.readGrade(target.room().getMaCodePhongThi(), studentId).orElseThrow().result();
            } catch (AuthException known) { throw known; }
            catch (Exception unavailable) { throw new AuthException(503, "Saved grading details unavailable."); }
        }
    }

    public Outcome grade(Request request, String expectedRunId, String reason) {
        Objects.requireNonNull(request);
        if (!Set.of("manual", "timeout").contains(Objects.toString(reason, ""))) throw new AuthException(422, "Invalid submission reason.");
        if (expectedRunId != null) expectedRunId = DtoValidator.nonEmpty(expectedRunId, "runId");
        final String expected = expectedRunId;
        // Same monitor used by every RuntimeStore reader/writer, across separate Service instances.
        // Enter before borrowing/locking JDBC rows. Never acquire SocketFlowService's monitor here.
        synchronized (store) {
            try {
                Target target = transactions.execute(c -> target(c, request));
                String code = target.room().getMaCodePhongThi();
                if (target.result().getTrangThai() == TrangThaiBaiLam.HOAN_THANH) {
                    StoredGrade saved = store.readGrade(code, request.idHs()).orElseThrow();
                    matchingRun(expected, saved.runId());
                    Submission first = store.readFirst(code, request.idHs()).orElseThrow();
                    return outcome(saved.result(), first.receipt(true), target);
                }
                if (target.room().getTrangThaiPhong() == TrangThaiPhongThi.DANG_CHO)
                    throw new AuthException(409, "Exam has not started.");
                Run run = store.readSubmissionRun(code, request.idHs()); matchingRun(expected, run.runId());
                // Validate even null answers' IDs before freezing. Revalidate inside the final write transaction.
                transactions.execute(c -> { var current = target(c, request); keys(c, current, run, request); return null; });
                Optional<Submission> previous = store.readFirst(code, request.idHs());
                if (target.room().getTrangThaiPhong() == TrangThaiPhongThi.DA_DONG && previous.isEmpty())
                    throw new AuthException(409, "Closed rooms do not accept new submissions.");
                Receipt receipt;
                if (previous.isEmpty()) {
                    long received = clock.millis();
                    receipt = store.receiveFirst(code, new Submission(UUID.randomUUID().toString(), run.runId(), request.idHs(),
                            received, received > run.endsAt(), reason, false, request.selectedAnswers()));
                } else receipt = previous.get().receipt(true);
                Submission first = store.readFirst(code, request.idHs()).orElseThrow();
                Recovery recovery = store.readRecovery(code, request.idHs()).orElse(null);
                if (target.result().getTrangThai() == TrangThaiBaiLam.MAT_KET_NOI && recovery == null)
                    throw new AuthException(503, "Saved recovery details unavailable.");
                return writes.execute(code, request.idHs(), (connection, unit, before) -> {
                    if (!before.idPhongThi().equals(request.idPhongThi()) || !before.idDeThi().equals(run.idDeThi())
                            || before.resultState() == TrangThaiBaiLam.HOAN_THANH)
                        throw new AuthException(503, "Submission context changed. Please retry.");
                    Target current = target(connection, request);
                    Map<String, String> correct = keys(connection, current, run, request);
                    Grade grade = calculate(request.idHs(), correct, first.answers());
                    DbState after = new DbState(before.idPhongThi(), code, before.idDeThi(), before.thoiGianLamBai(),
                            before.roomState(), before.idHs(), before.idKq(), TrangThaiBaiLam.HOAN_THANH, grade.diemSo());
                    unit.prepareGrade(before, after, new StoredGrade(request.idPhongThi(), run.runId(), grade));
                    long version = recovery == null ? 1 : Math.addExact(recovery.stateVersion(), 1);
                    long changed = recovery == null ? clock.millis() : Math.max(clock.millis(), recovery.stateChangedAt());
                    unit.prepareRecovery(before, after, new Recovery(request.idHs(), current.student().getHoVaTen(), code,
                            request.idPhongThi(), TrangThaiBaiLam.HOAN_THANH, version, changed,
                            recovery == null ? null : recovery.disconnectedAt(), recovery == null ? null : recovery.frozenRemainingSeconds()));
                    KetQuaThi row = current.result(); row.setDiemSo(grade.diemSo()); row.setTrangThai(TrangThaiBaiLam.HOAN_THANH);
                    if (!submissions.updateUnfinished(connection, row)) throw new AuthException(503, "Unable to save the exam score. Please retry.");
                    return outcome(grade, receipt, current);
                }); // Coordinator commits, closes JDBC, then reconciles prepared files against committed DB.
            } catch (AuthException known) { throw known; }
            catch (Exception unavailable) { throw new AuthException(503, "Unable to save the exam score. Please retry."); }
        }
    }

    private Target target(Connection c, Request request) throws SQLException {
        var room = rooms.lockById(c, request.idPhongThi()).orElseThrow(GradingService::notFound);
        var student = students.lockInRoom(c, request.idHs(), request.idPhongThi()).orElseThrow(GradingService::notFound);
        var result = submissions.lockInRoom(c, request.idHs(), request.idPhongThi()).orElseThrow(GradingService::notFound);
        return new Target(room, student, result);
    }
    private Map<String, String> keys(Connection c, Target target, Run run, Request request) throws SQLException {
        if (target.room().getTrangThaiPhong() == TrangThaiPhongThi.DANG_CHO) throw new AuthException(409, "Exam has not started.");
        if (!target.room().getIdDeThi().equals(run.idDeThi()) || !request.idPhongThi().equals(run.idPhongThi()))
            throw new AuthException(409, "Exam session does not match.");
        var correct = new LinkedHashMap<String, String>();
        for (var key : exams.listCorrectAnswers(c, target.room().getIdDeThi())) {
            if (key.idCauHoi() == null || key.dapAnDung() == null || !Set.of("A", "B", "C", "D").contains(key.dapAnDung())
                    || correct.putIfAbsent(key.idCauHoi(), key.dapAnDung()) != null)
                throw new AuthException(503, "Exam answer keys are invalid.");
        }
        if (correct.isEmpty()) throw new AuthException(409, "Exam has no questions to grade.");
        if (!correct.keySet().equals(new HashSet<>(run.cauHoiList().stream().map(Question::idCauHoi).toList())))
            throw new AuthException(409, "Exam questions do not match the started session.");
        if (!correct.keySet().containsAll(request.danhSachCauTraLoi().stream().map(Answer::idCauHoi).toList()))
            throw new AuthException(422, "Submission contains questions from another exam.");
        return correct;
    }
    /** Package-visible pure formula: exact binary-double rounding, matching Python round(x, 2). */
    static double score(int correct, int total) {
        if (total < 1 || correct < 0 || correct > total) throw new IllegalArgumentException("Invalid question counts.");
        double value = ((double) correct / total) * 10;
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN).doubleValue();
    }
    static Grade calculate(String student, Map<String, String> keys, Map<String, String> answers) {
        if (keys.isEmpty() || keys.values().stream().anyMatch(k -> k == null || !Set.of("A", "B", "C", "D").contains(k)))
            throw new AuthException(503, "Exam answer keys are invalid.");
        if (!keys.keySet().containsAll(answers.keySet())) throw new AuthException(503, "Saved submission does not match this exam.");
        int correct = (int) keys.entrySet().stream().filter(k -> k.getValue().equals(answers.get(k.getKey()))).count();
        return new Grade(student, score(correct, keys.size()), correct, keys.size(), TrangThaiBaiLam.HOAN_THANH);
    }
    private static AuthException notFound() { return new AuthException(404, "Student result not found in this room."); }
    private static void matchingRun(String expected, String actual) {
        if (expected != null && !expected.equals(actual)) throw new AuthException(409, "Exam session does not match.");
    }
    private static Outcome outcome(Grade grade, Receipt receipt, Target target) {
        return new Outcome(grade, receipt, new Submitted(grade.idHs(), grade.diemSo(), grade.soCauDung(), grade.tongSoCau(),
                grade.trangThai(), target.room().getIdPhongThi(), target.room().getMaCodePhongThi(), target.student().getHoVaTen()));
    }
}
