package com.ezam.model;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Row bean for legacy models.py::PhongThi; only persisted columns, no relationships.
 * A no-arg bean is unpopulated: SQL NULL stays null. Insert-time defaults/IDs
 * must be assigned explicitly by the future Service/DAO, never by row setters.
 * This internal model is not an API response DTO; do not serialize it directly.
 */
public class PhongThi implements Serializable {
    private static final long serialVersionUID = 1L;

    /** PostgreSQL column: id_phong_thi. */
    private String idPhongThi;
    /** PostgreSQL column: id_gv. */
    private String idGv;
    /** PostgreSQL column: id_de_thi. */
    private String idDeThi;
    /** PostgreSQL column: ma_code_phong_thi. */
    private String maCodePhongThi;
    /** PostgreSQL column: ten_lop. */
    private String tenLop;
    /** PostgreSQL column: thoi_gian_lam_bai. */
    private Integer thoiGianLamBai;
    /** PostgreSQL column: ngay_tao_phong. */
    private LocalDate ngayTaoPhong;
    /** PostgreSQL column: trang_thai_phong. */
    private TrangThaiPhongThi trangThaiPhong;

    public PhongThi() {
    }

    public String getIdPhongThi() {
        return idPhongThi;
    }

    public void setIdPhongThi(String idPhongThi) {
        this.idPhongThi = idPhongThi;
    }

    public String getIdGv() {
        return idGv;
    }

    public void setIdGv(String idGv) {
        this.idGv = idGv;
    }

    public String getIdDeThi() {
        return idDeThi;
    }

    public void setIdDeThi(String idDeThi) {
        this.idDeThi = idDeThi;
    }

    public String getMaCodePhongThi() {
        return maCodePhongThi;
    }

    public void setMaCodePhongThi(String maCodePhongThi) {
        this.maCodePhongThi = maCodePhongThi;
    }

    public String getTenLop() {
        return tenLop;
    }

    public void setTenLop(String tenLop) {
        this.tenLop = tenLop;
    }

    public Integer getThoiGianLamBai() {
        return thoiGianLamBai;
    }

    public void setThoiGianLamBai(Integer thoiGianLamBai) {
        this.thoiGianLamBai = thoiGianLamBai;
    }

    public LocalDate getNgayTaoPhong() {
        return ngayTaoPhong;
    }

    public void setNgayTaoPhong(LocalDate ngayTaoPhong) {
        this.ngayTaoPhong = ngayTaoPhong;
    }

    public TrangThaiPhongThi getTrangThaiPhong() {
        return trangThaiPhong;
    }

    public void setTrangThaiPhong(TrangThaiPhongThi trangThaiPhong) {
        this.trangThaiPhong = trangThaiPhong;
    }
}
