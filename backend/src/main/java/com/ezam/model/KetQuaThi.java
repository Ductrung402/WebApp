package com.ezam.model;

import java.io.Serializable;

/**
 * Row bean for legacy models.py::KetQuaThi; only persisted columns, no relationships.
 * A no-arg bean is unpopulated: SQL NULL stays null. Insert-time defaults/IDs
 * must be assigned explicitly by the future Service/DAO, never by row setters.
 * This internal model is not an API response DTO; do not serialize it directly.
 */
public class KetQuaThi implements Serializable {
    private static final long serialVersionUID = 1L;

    /** PostgreSQL column: id_kq. */
    private String idKq;
    /** PostgreSQL column: id_hs. */
    private String idHs;
    /** PostgreSQL column: id_phong_thi. */
    private String idPhongThi;
    /** PostgreSQL column: diem_so. */
    private Double diemSo;
    /** PostgreSQL column: trang_thai. */
    private TrangThaiBaiLam trangThai;

    public KetQuaThi() {
    }

    public String getIdKq() {
        return idKq;
    }

    public void setIdKq(String idKq) {
        this.idKq = idKq;
    }

    public String getIdHs() {
        return idHs;
    }

    public void setIdHs(String idHs) {
        this.idHs = idHs;
    }

    public String getIdPhongThi() {
        return idPhongThi;
    }

    public void setIdPhongThi(String idPhongThi) {
        this.idPhongThi = idPhongThi;
    }

    public Double getDiemSo() {
        return diemSo;
    }

    public void setDiemSo(Double diemSo) {
        this.diemSo = diemSo;
    }

    public TrangThaiBaiLam getTrangThai() {
        return trangThai;
    }

    public void setTrangThai(TrangThaiBaiLam trangThai) {
        this.trangThai = trangThai;
    }
}
