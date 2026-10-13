# BACKEND DEVELOPMENT RULES

## 1. Tech Stack
- Framework: FastAPI (Python 3.10+)
- Database: PostgreSQL
- ORM: SQLAlchemy (async mode)
- Data Validation: Pydantic v2
- Realtime: python-socketio

## 2. Database & Class Diagram Strict Adherence
- TUYỆT ĐỐI tuân thủ thiết kế Sơ đồ Lớp. Không tự ý thêm trường dữ liệu (ví dụ: không thêm `createdAt` cho GiaoVien, không thêm `loiGiai` cho CauHoi).
- Quy ước đặt tên Database: Sử dụng `snake_case` cho tên bảng và trường (VD: `giao_vien`, `ma_gv`, `trang_thai_phong`).
- Quy ước đặt tên Model/Schema: Sử dụng `PascalCase` cho Tên Class, `snake_case` cho thuộc tính.
- Mọi quan hệ (Foreign Key, Enum) phải ánh xạ chính xác từ bản thiết kế gốc.

## 3. Business Logic Constraints
- API sinh đề thi: Bắt buộc gọi qua `services/gemini_service.py` trước khi lưu vào DB.
- Bảng `KetQuaThi` phải bắt buộc có trường `trang_thai` (Enum: DANG_THI, MAT_KET_NOI, HOAN_THANH).
- Code phải bắt lỗi (Try/Catch) đầy đủ và trả về HTTP Status Code chuẩn (200, 400, 401, 404, 500).