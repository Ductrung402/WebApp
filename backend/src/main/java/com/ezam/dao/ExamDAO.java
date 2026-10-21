package com.ezam.dao;

import com.ezam.model.CauHoi;
import com.ezam.model.DeThi;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exam aggregate SQL; all mutations use the Service's transaction and parent-row lock. */
public final class ExamDAO {
    private static final String SUMMARY = "id_de_thi, id_gv, ten_de_thi, khoi, so_luong_cau_hoi, kieu_tai_lieu, ngay_tao";
    private static final String DETAIL = SUMMARY + ", noi_dung_tai_lieu";
    public enum MutationResult { APPLIED, NOT_FOUND, REFERENCED }

    // Detached data projections, not public API DTOs. PublicQuestion has no answer-key field.
    public record PublicQuestion(String idCauHoi, String noiDung, String dapAnA, String dapAnB, String dapAnC, String dapAnD) { }
    public record CorrectAnswer(String idCauHoi, String dapAnDung) { }

    public void insert(Connection c, DeThi exam) throws SQLException {
        Jdbc.update(c, "INSERT INTO de_thi (" + DETAIL + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                exam.getIdDeThi(), exam.getIdGv(), exam.getTenDeThi(), exam.getKhoi(), exam.getSoLuongCauHoi(),
                exam.getKieuTaiLieu(), exam.getNgayTao(), exam.getNoiDungTaiLieu());
    }

    /** Summary deliberately does not load the potentially large source document. */
    public List<DeThi> listOwned(Connection c, String teacherId) throws SQLException {
        return Jdbc.query(c, "SELECT " + SUMMARY + " FROM de_thi WHERE id_gv = ? ORDER BY ngay_tao DESC NULLS LAST, id_de_thi",
                RowMappers::examSummary, teacherId);
    }

    public Optional<DeThi> findOwned(Connection c, String teacherId, String examId) throws SQLException {
        return Jdbc.one(c, "SELECT " + DETAIL + " FROM de_thi WHERE id_gv = ? AND id_de_thi = ?", RowMappers::exam, teacherId, examId);
    }

    public Optional<DeThi> lockOwned(Connection c, String teacherId, String examId) throws SQLException {
        Jdbc.requireTransaction(c);
        return Jdbc.one(c, "SELECT " + DETAIL + " FROM de_thi WHERE id_gv = ? AND id_de_thi = ? FOR UPDATE",
                RowMappers::exam, teacherId, examId);
    }

    public boolean isReferenced(Connection c, String examId) throws SQLException {
        return Jdbc.one(c, "SELECT 1 FROM phong_thi WHERE id_de_thi = ? LIMIT 1", r -> true, examId).orElse(false);
    }

    private MutationResult mutable(Connection c, String teacherId, String examId) throws SQLException {
        if (lockOwned(c, teacherId, examId).isEmpty()) return MutationResult.NOT_FOUND;
        return isReferenced(c, examId) ? MutationResult.REFERENCED : MutationResult.APPLIED;
    }

    /** Does not change ID/owner/date/count. Replace questions separately within the SAME transaction. */
    public MutationResult updateOwnedMetadata(Connection c, String teacherId, DeThi value) throws SQLException {
        var outcome = mutable(c, teacherId, value.getIdDeThi());
        if (outcome != MutationResult.APPLIED) return outcome;
        Jdbc.update(c, "UPDATE de_thi SET ten_de_thi = ?, khoi = ?, kieu_tai_lieu = ?, noi_dung_tai_lieu = ? WHERE id_de_thi = ? AND id_gv = ?",
                value.getTenDeThi(), value.getKhoi(), value.getKieuTaiLieu(), value.getNoiDungTaiLieu(), value.getIdDeThi(), teacherId);
        return outcome;
    }

    public MutationResult replaceOwnedQuestions(Connection c, String teacherId, String examId, List<CauHoi> questions) throws SQLException {
        Objects.requireNonNull(questions, "Questions are required.");
        for (CauHoi question : questions) {
            if (!examId.equals(question.getIdDeThi())) throw new IllegalArgumentException("Question parent mismatch.");
        }
        var outcome = mutable(c, teacherId, examId);
        if (outcome != MutationResult.APPLIED) return outcome;
        Jdbc.update(c, "DELETE FROM cau_hoi WHERE id_de_thi = ?", examId);
        for (CauHoi q : questions) {
            Jdbc.update(c, "INSERT INTO cau_hoi (id_cau_hoi, id_de_thi, noi_dung, dap_an_a, dap_an_b, dap_an_c, dap_an_d, dap_an_dung) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    q.getIdCauHoi(), examId, q.getNoiDung(), q.getDapAnA(), q.getDapAnB(), q.getDapAnC(), q.getDapAnD(), q.getDapAnDung());
        }
        Jdbc.update(c, "UPDATE de_thi SET so_luong_cau_hoi = ? WHERE id_de_thi = ? AND id_gv = ?", questions.size(), examId, teacherId);
        return outcome;
    }

    public MutationResult deleteOwned(Connection c, String teacherId, String examId) throws SQLException {
        var outcome = mutable(c, teacherId, examId);
        if (outcome != MutationResult.APPLIED) return outcome;
        Jdbc.update(c, "DELETE FROM cau_hoi WHERE id_de_thi = ?", examId);
        Jdbc.update(c, "DELETE FROM de_thi WHERE id_de_thi = ? AND id_gv = ?", examId, teacherId);
        return outcome;
    }

    public List<CauHoi> listOwnedQuestions(Connection c, String teacherId, String examId) throws SQLException {
        return Jdbc.query(c, "SELECT q.id_cau_hoi, q.id_de_thi, q.noi_dung, q.dap_an_a, q.dap_an_b, q.dap_an_c, q.dap_an_d, q.dap_an_dung "
                + "FROM cau_hoi q JOIN de_thi e ON e.id_de_thi = q.id_de_thi WHERE e.id_gv = ? AND e.id_de_thi = ? ORDER BY q.id_cau_hoi",
                RowMappers::question, teacherId, examId);
    }

    /** Service must verify room/admission before calling; the SQL whitelist omits the answer key. */
    public List<PublicQuestion> listPublicQuestions(Connection c, String examId) throws SQLException {
        return Jdbc.query(c, "SELECT id_cau_hoi, noi_dung, dap_an_a, dap_an_b, dap_an_c, dap_an_d FROM cau_hoi WHERE id_de_thi = ? ORDER BY id_cau_hoi",
                r -> new PublicQuestion(r.getString("id_cau_hoi"), r.getString("noi_dung"), r.getString("dap_an_a"),
                        r.getString("dap_an_b"), r.getString("dap_an_c"), r.getString("dap_an_d")), examId);
    }

    /** Private grading data only; never attach this projection to a student response. */
    public List<CorrectAnswer> listCorrectAnswers(Connection c, String examId) throws SQLException {
        return Jdbc.query(c, "SELECT id_cau_hoi, dap_an_dung FROM cau_hoi WHERE id_de_thi = ? ORDER BY id_cau_hoi",
                r -> new CorrectAnswer(r.getString("id_cau_hoi"), r.getString("dap_an_dung")), examId);
    }
}
