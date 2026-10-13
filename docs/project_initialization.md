# Task 1.1: Project Initialization

Backend sử dụng FastAPI; entry point là `backend/main.py`.
Router đặt trong `backend/app/api/`, schema Pydantic v2 trong
`backend/app/schemas/`, và service trong `backend/app/services/`.
Frontend được chuẩn bị trong `frontend/` cho React + Vite + TailwindCSS.

## Chạy local trên PowerShell

Từ thư mục gốc dự án, chạy:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\backend\requirements.txt
Set-Location .\backend
..\.venv\Scripts\python.exe -m uvicorn main:app --reload --host 127.0.0.1 --port 8000
```

Trong terminal khác:

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8000/api/health
```

Response:

```json
{"status": "ok", "app": "Quiz AI & Exam System"}
```

Swagger UI: <http://127.0.0.1:8000/docs>.
CORS cho phép `http://localhost:5173` và `http://localhost:3000`,
bao gồm credentials, methods và headers.

Endpoint health xác nhận API đang hoạt động; không truy cập PostgreSQL.
Response được kiểm tra bằng schema `HealthResponse`, với hai trường
`status` và `app` kiểu chuỗi Literal. Endpoint không ánh xạ entity database,
chuyển đổi trạng thái nghiệp vụ hoặc thực hiện transaction.

Task này giữ nguyên `backend/app/models/models.py`, cấu hình database và
`backend/init_db.py`. Không chạy khởi tạo bảng, triển khai JWT hoặc giao diện.
