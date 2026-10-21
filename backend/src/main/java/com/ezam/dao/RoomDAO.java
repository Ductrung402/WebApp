package com.ezam.dao;

import com.ezam.model.PhongThi;
import com.ezam.model.TrangThaiPhongThi;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/** Lock order: exam before new room; existing room before student/result. No lock upgrades to exam. */
public final class RoomDAO {
    private static final String COLUMNS = "id_phong_thi, id_gv, id_de_thi, ma_code_phong_thi, ten_lop, thoi_gian_lam_bai, ngay_tao_phong, trang_thai_phong";

    public boolean insertOwned(Connection c, String teacherId, PhongThi room) throws SQLException {
        Jdbc.requireTransaction(c);
        // Same parent lock used by exam edits/deletes. Do not trust room.idGv from a caller's bean.
        if (new ExamDAO().lockOwned(c, teacherId, room.getIdDeThi()).isEmpty()) return false;
        Jdbc.update(c, "INSERT INTO phong_thi (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS trangthaiphongthi))",
                room.getIdPhongThi(), teacherId, room.getIdDeThi(), room.getMaCodePhongThi(), room.getTenLop(),
                room.getThoiGianLamBai(), room.getNgayTaoPhong(), name(room.getTrangThaiPhong()));
        return true;
    }

    public boolean existsCode(Connection c, String code) throws SQLException {
        return Jdbc.one(c, "SELECT 1 FROM phong_thi WHERE ma_code_phong_thi = ?", r -> true, code).orElse(false);
    }

    public Optional<PhongThi> findOwned(Connection c, String teacherId, String roomId) throws SQLException {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM phong_thi WHERE id_gv = ? AND id_phong_thi = ?", RowMappers::room, teacherId, roomId);
    }

    public Optional<PhongThi> lockOwned(Connection c, String teacherId, String roomId) throws SQLException {
        Jdbc.requireTransaction(c);
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM phong_thi WHERE id_gv = ? AND id_phong_thi = ? FOR UPDATE",
                RowMappers::room, teacherId, roomId);
    }

    public Optional<PhongThi> findByCode(Connection c, String code) throws SQLException {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM phong_thi WHERE ma_code_phong_thi = ?", RowMappers::room, code);
    }

    public Optional<PhongThi> lockByCode(Connection c, String code) throws SQLException {
        Jdbc.requireTransaction(c);
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM phong_thi WHERE ma_code_phong_thi = ? FOR UPDATE", RowMappers::room, code);
    }

    /** Public submission lookup still needs admission checks by the Service, under this parent lock. */
    public Optional<PhongThi> lockById(Connection c, String roomId) throws SQLException {
        Jdbc.requireTransaction(c);
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM phong_thi WHERE id_phong_thi = ? FOR UPDATE", RowMappers::room, roomId);
    }

    /** Primitive conditional write; lifecycle rules/JWT/retry policy are Service responsibilities. */
    public boolean updateStateOwned(Connection c, String teacherId, String roomId,
            TrangThaiPhongThi expected, TrangThaiPhongThi next) throws SQLException {
        return Jdbc.update(c, "UPDATE phong_thi SET trang_thai_phong = CAST(? AS trangthaiphongthi) "
                + "WHERE id_gv = ? AND id_phong_thi = ? AND trang_thai_phong IS NOT DISTINCT FROM CAST(? AS trangthaiphongthi)",
                name(next), teacherId, roomId, name(expected)) == 1;
    }

    /** Explicit child-first aggregate deletion; no DELETE endpoint is introduced by this DAO. */
    public boolean deleteOwned(Connection c, String teacherId, String roomId) throws SQLException {
        var room = lockOwned(c, teacherId, roomId);
        if (room.isEmpty()) return false;
        // Acquire locks in the shared room -> students -> results order, then DELETE in FK order.
        Jdbc.query(c, "SELECT id_hs FROM hoc_sinh WHERE ma_code_phong_thi = ? ORDER BY id_hs FOR UPDATE",
                r -> r.getString("id_hs"), room.get().getMaCodePhongThi());
        Jdbc.query(c, "SELECT id_kq FROM ket_qua_thi WHERE id_phong_thi = ? ORDER BY id_kq FOR UPDATE",
                r -> r.getString("id_kq"), roomId);
        Jdbc.update(c, "DELETE FROM ket_qua_thi WHERE id_phong_thi = ?", roomId);
        Jdbc.update(c, "DELETE FROM hoc_sinh WHERE ma_code_phong_thi = ?", room.get().getMaCodePhongThi());
        return Jdbc.update(c, "DELETE FROM phong_thi WHERE id_gv = ? AND id_phong_thi = ?", teacherId, roomId) == 1;
    }

    private static String name(TrangThaiPhongThi value) { return value == null ? null : value.name(); }
}
