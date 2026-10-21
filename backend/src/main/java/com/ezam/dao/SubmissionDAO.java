package com.ezam.dao;

import com.ezam.model.KetQuaThi;
import com.ezam.model.TrangThaiBaiLam;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Result storage only: no score formula, runtime snapshots, commits or Socket broadcasts. */
public final class SubmissionDAO {
    private static final String COLUMNS = "k.id_kq, k.id_hs, k.id_phong_thi, k.diem_so, k.trang_thai";
    private static final String RELATION = " FROM ket_qua_thi k JOIN hoc_sinh h ON h.id_hs = k.id_hs "
            + "JOIN phong_thi p ON p.id_phong_thi = k.id_phong_thi AND p.ma_code_phong_thi = h.ma_code_phong_thi";

    /** Returns false for a mismatched admission; SQL/constraint errors propagate unchanged. */
    public boolean insert(Connection c, KetQuaThi result) throws SQLException {
        return Jdbc.update(c, "INSERT INTO ket_qua_thi (id_kq, id_hs, id_phong_thi, diem_so, trang_thai) "
                + "SELECT ?, h.id_hs, p.id_phong_thi, CAST(? AS double precision), CAST(? AS trangthaibailam) "
                + "FROM hoc_sinh h JOIN phong_thi p ON p.ma_code_phong_thi = h.ma_code_phong_thi "
                + "WHERE h.id_hs = ? AND p.id_phong_thi = ?", result.getIdKq(), result.getDiemSo(), name(result.getTrangThai()),
                result.getIdHs(), result.getIdPhongThi()) == 1;
    }

    public Optional<KetQuaThi> findInRoom(Connection c, String studentId, String roomId) throws SQLException {
        return Jdbc.one(c, "SELECT " + COLUMNS + RELATION + " WHERE k.id_hs = ? AND k.id_phong_thi = ?",
                RowMappers::submission, studentId, roomId);
    }

    /** Service locks room, then student, then result. Duplicates fail rather than choose one silently. */
    public Optional<KetQuaThi> lockInRoom(Connection c, String studentId, String roomId) throws SQLException {
        Jdbc.requireTransaction(c);
        return Jdbc.one(c, "SELECT " + COLUMNS + RELATION + " WHERE k.id_hs = ? AND k.id_phong_thi = ? ORDER BY k.id_kq FOR UPDATE OF k",
                RowMappers::submission, studentId, roomId);
    }

    public List<KetQuaThi> listByOwnedRoom(Connection c, String teacherId, String roomId) throws SQLException {
        return Jdbc.query(c, "SELECT " + COLUMNS + RELATION + " WHERE p.id_gv = ? AND p.id_phong_thi = ? ORDER BY k.id_kq",
                RowMappers::submission, teacherId, roomId);
    }

    /** Conditional primitive write; a completed result cannot be downgraded or overwritten. */
    public boolean updateUnfinished(Connection c, KetQuaThi result) throws SQLException {
        return Jdbc.update(c, "UPDATE ket_qua_thi k SET diem_so = CAST(? AS double precision), trang_thai = CAST(? AS trangthaibailam) "
                + "WHERE k.id_kq = ? AND k.id_hs = ? AND k.id_phong_thi = ? AND k.trang_thai <> CAST('HOAN_THANH' AS trangthaibailam) "
                + "AND EXISTS (SELECT 1 FROM hoc_sinh h JOIN phong_thi p ON p.ma_code_phong_thi = h.ma_code_phong_thi "
                + "WHERE h.id_hs = k.id_hs AND p.id_phong_thi = k.id_phong_thi)",
                result.getDiemSo(), name(result.getTrangThai()), result.getIdKq(), result.getIdHs(), result.getIdPhongThi()) == 1;
    }

    private static String name(TrangThaiBaiLam value) { return value == null ? null : value.name(); }
}
