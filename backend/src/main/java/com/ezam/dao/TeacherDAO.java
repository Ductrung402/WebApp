package com.ezam.dao;

import com.ezam.model.GiaoVien;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/** Internal auth records include the hash. Service must map to a safe DTO before responding. */
public final class TeacherDAO {
    private static final String COLUMNS = "id_gv, email, mat_khau, ho_ten, truong, tinh";

    public Optional<GiaoVien> findById(Connection c, String id) throws SQLException {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM giao_vien WHERE id_gv = ?", RowMappers::teacher, id);
    }

    public Optional<GiaoVien> lockById(Connection c, String id) throws SQLException {
        Jdbc.requireTransaction(c);
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM giao_vien WHERE id_gv = ? FOR UPDATE", RowMappers::teacher, id);
    }

    /** Service normalizes input; lower(column) also admits legacy mixed-case stored emails. */
    public Optional<GiaoVien> findByEmail(Connection c, String email) throws SQLException {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM giao_vien WHERE lower(email) = ?", RowMappers::teacher, email);
    }

    public void insert(Connection c, GiaoVien teacher) throws SQLException {
        Jdbc.update(c, "INSERT INTO giao_vien (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
                teacher.getIdGv(), teacher.getEmail(), teacher.getMatKhau(), teacher.getHoTen(), teacher.getTruong(), teacher.getTinh());
    }

    public boolean updateProfile(Connection c, String id, String name, String school, String province) throws SQLException {
        return Jdbc.update(c, "UPDATE giao_vien SET ho_ten = ?, truong = ?, tinh = ? WHERE id_gv = ?",
                name, school, province, id) == 1;
    }

    /** Hashing and old-password verification belong to the Service. */
    public boolean updatePasswordHash(Connection c, String id, String hash) throws SQLException {
        return Jdbc.update(c, "UPDATE giao_vien SET mat_khau = ? WHERE id_gv = ?", hash, id) == 1;
    }
}
