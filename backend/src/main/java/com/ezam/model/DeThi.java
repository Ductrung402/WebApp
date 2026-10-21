package com.ezam.model;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Row bean for legacy models.py::DeThi; only persisted columns, no relationships.
 * A no-arg bean is unpopulated: SQL NULL stays null. Insert-time defaults/IDs
 * must be assigned explicitly by the future Service/DAO, never by row setters.
 * This internal model is not an API response DTO; do not serialize it directly.
 */
public class DeThi implements Serializable {
    private static final long serialVersionUID = 1L;

    /** PostgreSQL column: id_de_thi. */
    private String idDeThi;
    /** PostgreSQL column: id_gv. */
    private String idGv;
    /** PostgreSQL column: ten_de_thi. */
    private String tenDeThi;
    /** PostgreSQL column: khoi. */
    private String khoi;
    /** PostgreSQL column: so_luong_cau_hoi. */
    private Integer soLuongCauHoi;
    /** PostgreSQL column: kieu_tai_lieu. */
    private String kieuTaiLieu;
    /** PostgreSQL column: noi_dung_tai_lieu. */
    private String noiDungTaiLieu;
    /** PostgreSQL column: ngay_tao. */
    private LocalDate ngayTao;

    public DeThi() {
    }

    public String getIdDeThi() {
        return idDeThi;
    }

    public void setIdDeThi(String idDeThi) {
        this.idDeThi = idDeThi;
    }

    public String getIdGv() {
        return idGv;
    }

    public void setIdGv(String idGv) {
        this.idGv = idGv;
    }

    public String getTenDeThi() {
        return tenDeThi;
    }

    public void setTenDeThi(String tenDeThi) {
        this.tenDeThi = tenDeThi;
    }

    public String getKhoi() {
        return khoi;
    }

    public void setKhoi(String khoi) {
        this.khoi = khoi;
    }

    public Integer getSoLuongCauHoi() {
        return soLuongCauHoi;
    }

    public void setSoLuongCauHoi(Integer soLuongCauHoi) {
        this.soLuongCauHoi = soLuongCauHoi;
    }

    public String getKieuTaiLieu() {
        return kieuTaiLieu;
    }

    public void setKieuTaiLieu(String kieuTaiLieu) {
        this.kieuTaiLieu = kieuTaiLieu;
    }

    public String getNoiDungTaiLieu() {
        return noiDungTaiLieu;
    }

    public void setNoiDungTaiLieu(String noiDungTaiLieu) {
        this.noiDungTaiLieu = noiDungTaiLieu;
    }

    public LocalDate getNgayTao() {
        return ngayTao;
    }

    public void setNgayTao(LocalDate ngayTao) {
        this.ngayTao = ngayTao;
    }
}
