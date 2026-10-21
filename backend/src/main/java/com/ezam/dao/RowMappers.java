package com.ezam.dao;

import com.ezam.model.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLDataException;
import java.time.LocalDate;

/** Exact persisted columns; SQL NULL stays null. These beans are not public response DTOs. */
final class RowMappers {
    private RowMappers() { }

    static GiaoVien teacher(ResultSet r) throws SQLException {
        GiaoVien value = new GiaoVien();
        value.setIdGv(r.getString("id_gv")); value.setEmail(r.getString("email"));
        value.setMatKhau(r.getString("mat_khau")); value.setHoTen(r.getString("ho_ten"));
        value.setTruong(r.getString("truong")); value.setTinh(r.getString("tinh")); return value;
    }

    static DeThi examSummary(ResultSet r) throws SQLException {
        DeThi value = new DeThi();
        value.setIdDeThi(r.getString("id_de_thi")); value.setIdGv(r.getString("id_gv"));
        value.setTenDeThi(r.getString("ten_de_thi")); value.setKhoi(r.getString("khoi"));
        value.setSoLuongCauHoi(r.getObject("so_luong_cau_hoi", Integer.class));
        value.setKieuTaiLieu(r.getString("kieu_tai_lieu")); value.setNgayTao(r.getObject("ngay_tao", LocalDate.class));
        return value;
    }

    static DeThi exam(ResultSet r) throws SQLException {
        DeThi value = examSummary(r); value.setNoiDungTaiLieu(r.getString("noi_dung_tai_lieu")); return value;
    }

    static CauHoi question(ResultSet r) throws SQLException {
        CauHoi value = new CauHoi();
        value.setIdCauHoi(r.getString("id_cau_hoi")); value.setIdDeThi(r.getString("id_de_thi"));
        value.setNoiDung(r.getString("noi_dung")); value.setDapAnA(r.getString("dap_an_a"));
        value.setDapAnB(r.getString("dap_an_b")); value.setDapAnC(r.getString("dap_an_c"));
        value.setDapAnD(r.getString("dap_an_d")); value.setDapAnDung(r.getString("dap_an_dung")); return value;
    }

    static PhongThi room(ResultSet r) throws SQLException {
        PhongThi value = new PhongThi();
        value.setIdPhongThi(r.getString("id_phong_thi")); value.setIdGv(r.getString("id_gv"));
        value.setIdDeThi(r.getString("id_de_thi")); value.setMaCodePhongThi(r.getString("ma_code_phong_thi"));
        value.setTenLop(r.getString("ten_lop")); value.setThoiGianLamBai(r.getObject("thoi_gian_lam_bai", Integer.class));
        value.setNgayTaoPhong(r.getObject("ngay_tao_phong", LocalDate.class));
        value.setTrangThaiPhong(enumValue(r.getString("trang_thai_phong"), TrangThaiPhongThi.class)); return value;
    }

    static HocSinh student(ResultSet r) throws SQLException {
        HocSinh value = new HocSinh(); value.setIdHs(r.getString("id_hs"));
        value.setHoVaTen(r.getString("ho_va_ten")); value.setMaCodePhongThi(r.getString("ma_code_phong_thi")); return value;
    }

    static KetQuaThi submission(ResultSet r) throws SQLException {
        KetQuaThi value = new KetQuaThi(); value.setIdKq(r.getString("id_kq")); value.setIdHs(r.getString("id_hs"));
        value.setIdPhongThi(r.getString("id_phong_thi")); value.setDiemSo(r.getObject("diem_so", Double.class));
        value.setTrangThai(enumValue(r.getString("trang_thai"), TrangThaiBaiLam.class)); return value;
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type) throws SQLException {
        if (value == null) return null;
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException invalid) { throw new SQLDataException("Unknown persisted enum value.", "22000"); }
    }
}
