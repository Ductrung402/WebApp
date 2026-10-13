import os
from pathlib import Path
from dotenv import load_dotenv
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

# init_db.py and Uvicorn must use the same settings, regardless of cwd/import order.
load_dotenv(Path(__file__).resolve().parents[2] / ".env")

# Cấu hình chuỗi kết nối (hỗ trợ đọc từ biến môi trường DATABASE_URL nếu có)
SQLALCHEMY_DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://postgres:1@localhost:5432/WebApp"
)

# Tạo Engine kết nối
engine = create_engine(SQLALCHEMY_DATABASE_URL)

# Tạo SessionFactory
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

# Dependency Injection cho FastAPI routes (theo hợp đồng .rules.md)
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
