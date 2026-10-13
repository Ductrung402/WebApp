import sys
from app.core.database import engine
from app.models.models import Base

def create_database():
    print("[INFO] Dang ket noi toi PostgreSQL...")
    try:
        # Quet toan bo cac model ke thua tu Base va tao bang neu chua ton tai
        Base.metadata.create_all(bind=engine)
        print("[SUCCESS] Da khoi tao toan bo cac bang trong Database thanh cong!")
        print("Danh sach cac bang: giao_vien, de_thi, cau_hoi, phong_thi, hoc_sinh, ket_qua_thi.")
    except Exception as exc:
        print(f"[ERROR] Loi khi khoi tao Database: {exc}", file=sys.stderr)
        print("[TIP] Hay dam bao rang PostgreSQL dang chay va database 'WebApp' da duoc tao tren PostgreSQL.", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    create_database()