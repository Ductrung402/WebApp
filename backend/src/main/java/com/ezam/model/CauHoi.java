package com.ezam.model;

import java.io.Serializable;

/**
 * Row bean for legacy models.py::CauHoi; only persisted columns, no relationships.
 * A no-arg bean is unpopulated: SQL NULL stays null. Insert-time defaults/IDs
 * must be assigned explicitly by the future Service/DAO, never by row setters.
 * This internal model is not an API response DTO; do not serialize it directly.
 */
public class CauHoi implements Serializable {
    private static final long serialVersionUID = 1L;

    /** PostgreSQL column: id_cau_hoi. */
    private String idCauHoi;
    /** PostgreSQL column: id_de_thi. */
    private String idDeThi;
    /** PostgreSQL column: noi_dung. */
    private String noiDung;
    /** PostgreSQL column: dap_an_a. */
    private String dapAnA;
    /** PostgreSQL column: dap_an_b. */
    private String dapAnB;
    /** PostgreSQL column: dap_an_c. */
    private String dapAnC;
    /** PostgreSQL column: dap_an_d. */
    private String dapAnD;
    /** PostgreSQL column: dap_an_dung. */
    private String dapAnDung;

    public CauHoi() {
    }

    public String getIdCauHoi() {
        return idCauHoi;
    }

    public void setIdCauHoi(String idCauHoi) {
        this.idCauHoi = idCauHoi;
    }

    public String getIdDeThi() {
        return idDeThi;
    }

    public void setIdDeThi(String idDeThi) {
        this.idDeThi = idDeThi;
    }

    public String getNoiDung() {
        return noiDung;
    }

    public void setNoiDung(String noiDung) {
        this.noiDung = noiDung;
    }

    public String getDapAnA() {
        return dapAnA;
    }

    public void setDapAnA(String dapAnA) {
        this.dapAnA = dapAnA;
    }

    public String getDapAnB() {
        return dapAnB;
    }

    public void setDapAnB(String dapAnB) {
        this.dapAnB = dapAnB;
    }

    public String getDapAnC() {
        return dapAnC;
    }

    public void setDapAnC(String dapAnC) {
        this.dapAnC = dapAnC;
    }

    public String getDapAnD() {
        return dapAnD;
    }

    public void setDapAnD(String dapAnD) {
        this.dapAnD = dapAnD;
    }

    public String getDapAnDung() {
        return dapAnDung;
    }

    public void setDapAnDung(String dapAnDung) {
        this.dapAnDung = dapAnDung;
    }
}
