package com.ezam.model;

import java.io.Serializable;

/**
 * Row bean for legacy models.py::GiaoVien; only persisted columns, no relationships.
 * A no-arg bean is unpopulated: SQL NULL stays null. Insert-time defaults/IDs
 * must be assigned explicitly by the future Service/DAO, never by row setters.
 * This internal model is not an API response DTO; do not serialize it directly.
 */
public class GiaoVien implements Serializable {
    private static final long serialVersionUID = 1L;

    /** PostgreSQL column: id_gv. */
    private String idGv;
    /** PostgreSQL column: email. */
    private String email;
    /** PostgreSQL column: mat_khau. */
    private String matKhau;
    /** PostgreSQL column: ho_ten. */
    private String hoTen;
    /** PostgreSQL column: truong. */
    private String truong;
    /** PostgreSQL column: tinh. */
    private String tinh;

    public GiaoVien() {
    }

    public String getIdGv() {
        return idGv;
    }

    public void setIdGv(String idGv) {
        this.idGv = idGv;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getMatKhau() {
        return matKhau;
    }

    public void setMatKhau(String matKhau) {
        this.matKhau = matKhau;
    }

    public String getHoTen() {
        return hoTen;
    }

    public void setHoTen(String hoTen) {
        this.hoTen = hoTen;
    }

    public String getTruong() {
        return truong;
    }

    public void setTruong(String truong) {
        this.truong = truong;
    }

    public String getTinh() {
        return tinh;
    }

    public void setTinh(String tinh) {
        this.tinh = tinh;
    }
}
