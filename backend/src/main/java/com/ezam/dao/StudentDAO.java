package com.ezam.dao;

import com.ezam.model.HocSinh;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public final class StudentDAO {
    private static final String COLUMNS = "h.id_hs, h.ho_va_ten, h.ma_code_phong_thi";
    private static final String IN_ROOM = " FROM hoc_sinh h JOIN phong_thi p ON p.ma_code_phong_thi = h.ma_code_phong_thi "
            + "WHERE h.id_hs = ? AND p.id_phong_thi = ?";

    /** Service locks/checks room state first and inserts the result in this same Connection. */
    public void insert(Connection c, HocSinh student) throws SQLException {
        Jdbc.update(c, "INSERT INTO hoc_sinh (id_hs, ho_va_ten, ma_code_phong_thi) VALUES (?, ?, ?)",
                student.getIdHs(), student.getHoVaTen(), student.getMaCodePhongThi());
    }

    public Optional<HocSinh> findInRoom(Connection c, String studentId, String roomId) throws SQLException {
        return Jdbc.one(c, "SELECT " + COLUMNS + IN_ROOM, RowMappers::student, studentId, roomId);
    }

    /** Lock the room first. OF h deliberately avoids implicitly locking a second table. */
    public Optional<HocSinh> lockInRoom(Connection c, String studentId, String roomId) throws SQLException {
        Jdbc.requireTransaction(c);
        return Jdbc.one(c, "SELECT " + COLUMNS + IN_ROOM + " FOR UPDATE OF h", RowMappers::student, studentId, roomId);
    }

    public List<HocSinh> listByOwnedRoom(Connection c, String teacherId, String roomId) throws SQLException {
        return Jdbc.query(c, "SELECT " + COLUMNS + " FROM hoc_sinh h JOIN phong_thi p ON p.ma_code_phong_thi = h.ma_code_phong_thi "
                + "WHERE p.id_gv = ? AND p.id_phong_thi = ? ORDER BY h.ho_va_ten, h.id_hs", RowMappers::student, teacherId, roomId);
    }
}
