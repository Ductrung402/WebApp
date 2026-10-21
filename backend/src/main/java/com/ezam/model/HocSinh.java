package com.ezam.model;

import java.io.Serializable;

/**
 * Row bean for legacy models.py::HocSinh; only persisted columns, no relationships.
 * A no-arg bean is unpopulated: SQL NULL stays null. Insert-time defaults/IDs
 * must be assigned explicitly by the future Service/DAO, never by row setters.
 * This internal model is not an API response DTO; do not serialize it directly.
 */
public class HocSinh implements Serializable {
    private static final long serialVersionUID = 1L;

    /** PostgreSQL column: id_hs. */
    private String idHs;
    /** PostgreSQL column: ho_va_ten. */
    private String hoVaTen;
    /** PostgreSQL column: ma_code_phong_thi. */
    private String maCodePhongThi;

    public HocSinh() {
    }

    public String getIdHs() {
        return idHs;
    }

    public void setIdHs(String idHs) {
        this.idHs = idHs;
    }

    public String getHoVaTen() {
        return hoVaTen;
    }

    public void setHoVaTen(String hoVaTen) {
        this.hoVaTen = hoVaTen;
    }

    public String getMaCodePhongThi() {
        return maCodePhongThi;
    }

    public void setMaCodePhongThi(String maCodePhongThi) {
        this.maCodePhongThi = maCodePhongThi;
    }
}
