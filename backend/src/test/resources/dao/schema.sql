-- Test-only mirror of T1.1 public catalog (38 columns, 6 PK, 7 FK, 2 UNIQUE).
-- Deliberately NO server defaults, ON DELETE CASCADE or UNIQUE ket_qua_thi.id_hs.
CREATE TYPE trangthaiphongthi AS ENUM ('DANG_CHO', 'DANG_THI', 'DA_DONG');
CREATE TYPE trangthaibailam AS ENUM ('DANG_THI', 'MAT_KET_NOI', 'HOAN_THANH');
CREATE TABLE giao_vien (
    id_gv varchar PRIMARY KEY, email varchar NOT NULL UNIQUE, mat_khau varchar NOT NULL,
    ho_ten varchar NOT NULL, truong varchar, tinh varchar
);
CREATE TABLE de_thi (
    id_de_thi varchar PRIMARY KEY, id_gv varchar NOT NULL REFERENCES giao_vien(id_gv),
    ten_de_thi varchar NOT NULL, khoi varchar, so_luong_cau_hoi integer, kieu_tai_lieu varchar NOT NULL,
    noi_dung_tai_lieu text, ngay_tao date
);
CREATE TABLE cau_hoi (
    id_cau_hoi varchar PRIMARY KEY, id_de_thi varchar NOT NULL REFERENCES de_thi(id_de_thi),
    noi_dung text NOT NULL, dap_an_a varchar NOT NULL, dap_an_b varchar NOT NULL,
    dap_an_c varchar NOT NULL, dap_an_d varchar NOT NULL, dap_an_dung varchar NOT NULL
);
CREATE TABLE phong_thi (
    id_phong_thi varchar PRIMARY KEY, id_gv varchar NOT NULL REFERENCES giao_vien(id_gv),
    id_de_thi varchar NOT NULL REFERENCES de_thi(id_de_thi), ma_code_phong_thi varchar NOT NULL UNIQUE,
    ten_lop varchar NOT NULL, thoi_gian_lam_bai integer NOT NULL, ngay_tao_phong date,
    trang_thai_phong trangthaiphongthi
);
CREATE TABLE hoc_sinh (
    id_hs varchar PRIMARY KEY, ho_va_ten varchar NOT NULL,
    ma_code_phong_thi varchar NOT NULL REFERENCES phong_thi(ma_code_phong_thi)
);
CREATE TABLE ket_qua_thi (
    id_kq varchar PRIMARY KEY, id_hs varchar NOT NULL REFERENCES hoc_sinh(id_hs),
    id_phong_thi varchar NOT NULL REFERENCES phong_thi(id_phong_thi), diem_so double precision,
    trang_thai trangthaibailam
);
