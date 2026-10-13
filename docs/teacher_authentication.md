# Task 1.3: Teacher Authentication

## Phạm vi

- `POST /api/auth/register`: JSON body, trả HTTP 201 và thông tin giáo viên.
- `POST /api/auth/login`: JSON body, trả HTTP 200 và JWT access token.
- `GET /api/auth/me`: gửi `Authorization: Bearer <access_token>`, trả thông tin giáo viên.
- Response giáo viên chỉ có `id_gv`, `email`, `ho_ten`, `truong`, `tinh`;
  không có `mat_khau`. JWT chỉ chứa `sub` (ID giáo viên), `iat`, `exp`.
- Email được kiểm tra format và chuẩn hóa chữ thường. Mật khẩu cần ít nhất
  8 ký tự, tối đa 72 byte UTF-8, không chứa null byte. Password không bị strip.
- `truong` và `tinh` có thể bỏ qua hoặc gửi `null`, khớp nullable trong model.
- Payload không hợp lệ trả 422 trước truy vấn DB. Email trùng trả 409.
- Sai thông tin đăng nhập, token thiếu/sai/hết hạn hoặc giáo viên không còn
  tồn tại trả 401 với header `WWW-Authenticate: Bearer`.
- Lỗi SQLAlchemy khi xác thực/lưu tài khoản trả 503; transaction được rollback.
- Router chỉ gọi service/dependency; bcrypt và JWT đặt trong `core/security.py`.

## Cấu hình và chạy local (PowerShell)

Dùng Python ổn định từ 3.10 trở lên. Python 3.11.0b1 trên máy hiện tại gây
lỗi import SQLAlchemy với typing-extensions mới; `.venv` đã được chuẩn bị
bằng Python 3.11 ổn định riêng trong dự án.

Từ thư mục gốc dự án:

```powershell
# Khi thiết lập trên máy mới, dùng Python ổn định để tạo môi trường:
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\backend\requirements.txt
```

`backend/.env` đã được tạo với JWT key ngẫu nhiên riêng cho local và được
loại khỏi Git. Khi thiết lập trên máy mới, sao chép `.env.example` thành
`.env` và điền key bằng output của lệnh sau:

```powershell
.\.venv\Scripts\python.exe -c "import secrets; print(secrets.token_urlsafe(48))"
```

```dotenv
JWT_SECRET_KEY=<private-random-key>
ACCESS_TOKEN_EXPIRE_MINUTES=30
```

Không dùng placeholder làm key. Ứng dụng kiểm tra key tối thiểu 32 byte và
thời hạn token là số nguyên dương khi khởi động. Biến môi trường có ưu tiên
cao hơn `.env`. Kết nối PostgreSQL giữ nguyên cấu hình Task 1.2; có thể đặt
`DATABASE_URL` trong môi trường hoặc `.env` để ghi đè.

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -m uvicorn main:app --reload --host 127.0.0.1 --port 8000
```

PostgreSQL cần có bảng `giao_vien` đã tạo từ Task 1.2. Task này không chạy
`init_db.py`, không thay đổi bảng/model và không tạo thêm endpoint nghiệp vụ.

## Mẫu cURL

Trong terminal khác, tại thư mục gốc dự án:

```powershell
curl.exe -i -H "Content-Type: application/json" --data-binary "@docs/auth_register.json" http://127.0.0.1:8000/api/auth/register
curl.exe -i -H "Content-Type: application/json" --data-binary "@docs/auth_login.json" http://127.0.0.1:8000/api/auth/login
curl.exe -i -H "Authorization: Bearer <access_token>" http://127.0.0.1:8000/api/auth/me
```

Chạy register lần đầu trả 201; chạy lại với cùng email trả 409.
Response register/me có dạng:

```json
{
  "id_gv": "<generated-uuid-string>",
  "email": "teacher@example.com",
  "ho_ten": "Nguyen Van A",
  "truong": "THPT A",
  "tinh": "TP HCM"
}
```

Response login có dạng:

```json
{"access_token": "<signed-jwt>", "token_type": "bearer"}
```

Swagger `/docs` dùng nút Authorize với JWT lấy từ JSON endpoint login.
API này sử dụng HTTP Bearer và không yêu cầu OAuth2 form login.

## Bằng chứng kiểm thử

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
..\.venv\Scripts\python.exe -m pip check
```

Test dùng SQLite trong bộ nhớ và bảng được tạo từ chính model `GiaoVien`
hiện có; không truy cập hay thay đổi dữ liệu PostgreSQL local. Các test
kiểm tra luồng register/login/me, hash bcrypt thực sự lưu trong DB, email
trùng, validation trước DB, JWT lỗi/hết hạn/sai khóa/sai thuật toán/thiếu
claim, trường nullable và response không lộ mật khẩu. Test cố ý gây lỗi
sau INSERT/ở commit để xác minh rollback và không lưu tài khoản dở dang.

Kết quả đã chạy: 12 test đều đạt; `pip check` không có dependency lỗi.
Đã kiểm thử thêm trên PostgreSQL local, sử dụng cùng router/service với
Session trong savepoint của một transaction ngoài: register trả 201,
login trả 200, me trả 200, email trùng trả 409. Transaction ngoài được
rollback sau kiểm thử và truy vấn lại xác nhận tài khoản mẫu không còn.
Không tạo/thay đổi bảng hoặc cột PostgreSQL trong quá trình kiểm thử này.

SHA-256 của `models.py` trước và sau Task 1.3 giống nhau:
`D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F`.

Schema verification: `backend/app/schemas/auth.py`, class
`TeacherResponseSchema`, có ID chuỗi, email/ho_ten chuỗi và truong/tinh
chuỗi hoặc null; không khai báo trường mật khẩu.

Transaction safety: `register_teacher` trong `services/auth_service.py`
thực hiện `db.add`, `db.flush`, validate response, `db.commit` trong khối
`try`; mọi lỗi ghi dữ liệu kích hoạt `db.rollback()` trước khi trả lỗi.
