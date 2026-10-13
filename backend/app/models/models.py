from sqlalchemy import Column, String, Integer, Float, Date, ForeignKey, Enum, Text
from sqlalchemy.orm import relationship, declarative_base
import enum
from datetime import date
import uuid

Base = declarative_base()

# ==========================================
# ENUMS
# ==========================================
class TrangThaiBaiLam(str, enum.Enum):
    DANG_THI = "DANG_THI"
    MAT_KET_NOI = "MAT_KET_NOI"
    HOAN_THANH = "HOAN_THANH"

class TrangThaiPhongThi(str, enum.Enum):
    DANG_CHO = "DANG_CHO"
    DANG_THI = "DANG_THI"
    DA_DONG = "DA_DONG"


# ==========================================
# MODELS (Ánh xạ 1:1 với Sơ đồ Lớp)
# ==========================================
class GiaoVien(Base):
    __tablename__ = "giao_vien"

    id_gv = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    email = Column(String, unique=True, nullable=False)
    mat_khau = Column(String, nullable=False)
    ho_ten = Column(String, nullable=False)
    truong = Column(String, nullable=True)
    tinh = Column(String, nullable=True)

    # Relationships
    de_thi_list = relationship("DeThi", back_populates="giao_vien", cascade="all, delete-orphan")
    phong_thi_list = relationship("PhongThi", back_populates="giao_vien")


class DeThi(Base):
    __tablename__ = "de_thi"

    id_de_thi = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    id_gv = Column(String, ForeignKey("giao_vien.id_gv"), nullable=False)
    ten_de_thi = Column(String, nullable=False)
    khoi = Column(String, nullable=True)
    so_luong_cau_hoi = Column(Integer, default=0)
    kieu_tai_lieu = Column(String, nullable=False) # VD: 'TEXT' hoặc 'FILE'
    noi_dung_tai_lieu = Column(Text, nullable=True)
    ngay_tao = Column(Date, default=date.today)

    # Relationships
    giao_vien = relationship("GiaoVien", back_populates="de_thi_list")
    cau_hoi_list = relationship("CauHoi", back_populates="de_thi", cascade="all, delete-orphan") # Bao hàm (◆)
    phong_thi_list = relationship("PhongThi", back_populates="de_thi")


class CauHoi(Base):
    __tablename__ = "cau_hoi"

    id_cau_hoi = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    id_de_thi = Column(String, ForeignKey("de_thi.id_de_thi"), nullable=False)
    noi_dung = Column(Text, nullable=False)
    dap_an_a = Column(String, nullable=False)
    dap_an_b = Column(String, nullable=False)
    dap_an_c = Column(String, nullable=False)
    dap_an_d = Column(String, nullable=False)
    dap_an_dung = Column(String, nullable=False) # Lưu 'A', 'B', 'C', 'D'

    # Relationships
    de_thi = relationship("DeThi", back_populates="cau_hoi_list")


class PhongThi(Base):
    __tablename__ = "phong_thi"

    id_phong_thi = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    id_gv = Column(String, ForeignKey("giao_vien.id_gv"), nullable=False)
    id_de_thi = Column(String, ForeignKey("de_thi.id_de_thi"), nullable=False)
    ma_code_phong_thi = Column(String, unique=True, nullable=False) # Sinh tự động 6 ký tự
    ten_lop = Column(String, nullable=False)
    thoi_gian_lam_bai = Column(Integer, nullable=False) # Tính bằng phút
    ngay_tao_phong = Column(Date, default=date.today)
    trang_thai_phong = Column(Enum(TrangThaiPhongThi), default=TrangThaiPhongThi.DANG_CHO)

    # Relationships
    giao_vien = relationship("GiaoVien", back_populates="phong_thi_list")
    de_thi = relationship("DeThi", back_populates="phong_thi_list")
    hoc_sinh_list = relationship("HocSinh", back_populates="phong_thi", cascade="all, delete-orphan") # Bao hàm (◆)
    ket_qua_list = relationship("KetQuaThi", back_populates="phong_thi", cascade="all, delete-orphan") # Bao hàm (◆)


class HocSinh(Base):
    __tablename__ = "hoc_sinh"

    id_hs = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    ho_va_ten = Column(String, nullable=False)
    ma_code_phong_thi = Column(String, ForeignKey("phong_thi.ma_code_phong_thi"), nullable=False)
    
    # Relationships
    phong_thi = relationship("PhongThi", back_populates="hoc_sinh_list")
    ket_qua_thi = relationship("KetQuaThi", back_populates="hoc_sinh", uselist=False) # Quan hệ 1-1


class KetQuaThi(Base):
    __tablename__ = "ket_qua_thi"

    id_kq = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    id_hs = Column(String, ForeignKey("hoc_sinh.id_hs"), nullable=False)
    id_phong_thi = Column(String, ForeignKey("phong_thi.id_phong_thi"), nullable=False)
    diem_so = Column(Float, default=0.0)
    trang_thai = Column(Enum(TrangThaiBaiLam), default=TrangThaiBaiLam.DANG_THI)

    # Relationships
    hoc_sinh = relationship("HocSinh", back_populates="ket_qua_thi")
    phong_thi = relationship("PhongThi", back_populates="ket_qua_list")