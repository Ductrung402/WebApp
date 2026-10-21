package com.ezam.service;

import com.ezam.dao.ExamDAO;
import com.ezam.dto.ExamDTO;
import com.ezam.model.*;
import com.ezam.util.AuthException;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** Owned CRUD aggregate; one JDBC transaction for exam and all questions. No Gemini calls. */
public final class ExamService {
    private final TransactionManager transactions;
    private final ExamDAO exams;
    private final Clock clock;
    public ExamService(TransactionManager transactions, ExamDAO exams, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions); this.exams = Objects.requireNonNull(exams); this.clock = Objects.requireNonNull(clock);
    }
    private <T> T read(TransactionManager.Work<T> work) {
        try { return transactions.execute(work); }
        catch (SQLException failure) { throw new AuthException(503, "Exam database unavailable."); }
    }
    private <T> T write(TransactionManager.Work<T> work) {
        try { return transactions.execute(work); }
        catch (SQLException failure) {
            if (failure.getSQLState() != null && failure.getSQLState().startsWith("23")) throw new AuthException(409, "Exam data conflicts with existing records.");
            throw new AuthException(503, "Exam database unavailable.");
        }
    }
    private static void outcome(ExamDAO.MutationResult result) {
        if (result == ExamDAO.MutationResult.NOT_FOUND) throw new AuthException(404, "Exam not found.");
        if (result == ExamDAO.MutationResult.REFERENCED) throw new AuthException(409, "An exam referenced by an exam room cannot be changed or deleted.");
    }
    private static void metadata(DeThi value, ExamDTO.Manual payload) {
        value.setTenDeThi(payload.tenDeThi()); value.setKhoi(payload.khoi()); value.setKieuTaiLieu(payload.kieuTaiLieu()); value.setNoiDungTaiLieu(payload.noiDungTaiLieu());
        value.setSoLuongCauHoi(payload.cauHoiList().size());
    }
    public ExamDTO.Detail create(String teacherId, ExamDTO.Manual payload) {
        return write(c -> {
            var exam = new DeThi(); exam.setIdDeThi(UUID.randomUUID().toString()); exam.setIdGv(teacherId); exam.setNgayTao(LocalDate.now(clock)); metadata(exam, payload);
            List<CauHoi> questions = payload.cauHoiList().stream().map(q -> q.row(exam.getIdDeThi())).toList();
            exams.insert(c, exam); outcome(exams.replaceOwnedQuestions(c, teacherId, exam.getIdDeThi(), questions));
            return ExamDTO.detail(exam, questions); // validate all output before the single COMMIT
        });
    }
    public List<ExamDTO.Summary> list(String teacherId) { return read(c -> exams.listOwned(c, teacherId).stream().map(ExamDTO::summary).toList()); }
    public ExamDTO.Detail get(String teacherId, String id) {
        return read(c -> {
            var exam = exams.findOwned(c, teacherId, id).orElseThrow(() -> new AuthException(404, "Exam not found."));
            return ExamDTO.detail(exam, exams.listOwnedQuestions(c, teacherId, id));
        });
    }
    public ExamDTO.Detail update(String teacherId, String id, ExamDTO.Manual payload) {
        return write(c -> {
            var exam = exams.lockOwned(c, teacherId, id).orElseThrow(() -> new AuthException(404, "Exam not found.")); metadata(exam, payload);
            outcome(exams.updateOwnedMetadata(c, teacherId, exam));
            var questions = payload.cauHoiList().stream().map(q -> q.row(id)).toList();
            outcome(exams.replaceOwnedQuestions(c, teacherId, id, questions));
            return ExamDTO.detail(exam, questions);
        });
    }
    public ExamDTO.Deleted delete(String teacherId, String id) {
        return write(c -> { outcome(exams.deleteOwned(c, teacherId, id)); return new ExamDTO.Deleted(id); });
    }
}
