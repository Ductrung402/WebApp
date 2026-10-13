# Quiz AI & Exam System

Đồ án Lập trình Web: giáo viên tạo đề trắc nghiệm từ tài liệu với Gemini, kiểm duyệt câu hỏi, tổ chức phòng thi trực tiếp và xem bảng điểm. Học sinh tham gia bằng họ tên và mã phòng, không cần tài khoản.

## Công nghệ và cấu trúc

React 18, Vite, TailwindCSS, React Router v6; FastAPI, Pydantic v2, SQLAlchemy, PostgreSQL; Socket.IO; Google GenAI SDK. Backend hiện sử dụng SQLAlchemy Session đồng bộ; các tác vụ database trong Socket handler được chuyển sang worker thread.

```text
WebApp/
├── backend/
│   ├── app/
│   │   ├── api/                  # Router REST mỏng
│   │   ├── core/                 # Cấu hình, database, JWT, Socket, runtime
│   │   ├── models/models.py      # Sơ đồ dữ liệu cố định
│   │   ├── schemas/              # Hợp đồng Pydantic v2
│   │   └── services/             # Auth, Gemini, đề/phòng thi, khôi phục, chấm điểm
│   ├── tests/
│   ├── .env.example
│   ├── init_db.py
│   ├── main.py
│   ├── requirements.txt
│   └── verify_e2e.py
├── frontend/
│   ├── src/{components,contexts,hooks,layouts,pages,services,utils}/
│   ├── tests/                    # Kiểm thử UI và các tình huống lỗi
│   ├── scripts/verify-e2e.mjs     # Chrome E2E với backend/Gemini thật
│   ├── .env.example
│   └── package.json
├── docs/
│   ├── API_DOCUMENTATION.md
│   ├── COURSE_REPORT.md
│   ├── QA_REPORT.md
│   └── qa/                       # JSON, CSV và ảnh E2E
├── overview.md
├── .rules.md
└── master_plan.md
```

## Setup từ A–Z trên Windows PowerShell

Chạy các lệnh tại **thư mục gốc WebApp**. Cần Python 3.11, Node.js 22/npm, PostgreSQL đang chạy. Google Chrome cần cho kiểm thử trình duyệt. Máy phát triển đã nghiệm thu bằng Python 3.11 và Node.js 22.

### 1. Tạo môi trường và cài dependencies

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r backend\requirements.txt
npm.cmd --prefix frontend ci
if (!(Test-Path backend\.env)) { Copy-Item backend\.env.example backend\.env }
if (!(Test-Path frontend\.env)) { Copy-Item frontend\.env.example frontend\.env }
```

Chỉ sao chép `.env.example` khi chưa có `.env`, để giữ cấu hình đã thiết lập. Dùng `npm.cmd` trên PowerShell để tránh lỗi ExecutionPolicy với `npm.ps1`.

### 2. Cấu hình PostgreSQL và khóa bí mật

Tạo database `WebApp` bằng pgAdmin hoặc psql; nếu đã tồn tại thì dùng lại:

```powershell
psql -U postgres -h localhost -d postgres
```

Trong psql chạy SQL sau, rồi nhập `\q` để thoát. Nếu `psql` chưa nằm trong PATH, chạy cùng SQL trong pgAdmin kết nối database `postgres`:

```sql
CREATE DATABASE "WebApp";
```

Mở `backend/.env`, điền các giá trị của máy bạn:

```dotenv
DATABASE_URL=postgresql+psycopg2://postgres:YOUR_PASSWORD@localhost:5432/WebApp
JWT_SECRET_KEY=YOUR_RANDOM_PRIVATE_KEY_AT_LEAST_32_BYTES
ACCESS_TOKEN_EXPIRE_MINUTES=30
GEMINI_API_KEY=YOUR_GEMINI_API_KEY
GEMINI_MODEL=gemini-3.5-flash-lite
```

Sinh một khóa JWT riêng, sao chép kết quả vào `.env`:

```powershell
.\.venv\Scripts\python.exe -c "import secrets; print(secrets.token_urlsafe(48))"
```

Mật khẩu database chứa ký tự đặc biệt cần được URL-encode. `DATABASE_URL` và khóa chỉ nằm ở backend. Biến môi trường hệ điều hành có ưu tiên cao hơn `.env`. Model Gemini có thể đổi qua `GEMINI_MODEL` theo quyền truy cập API key của bạn.

`frontend/.env`:

```dotenv
VITE_API_BASE_URL=http://localhost:8000
VITE_SOCKET_URL=http://localhost:8000
```

Các biến `VITE_` được đưa vào bundle trình duyệt; không đặt API key/JWT secret tại đây. Khởi động lại Vite sau khi sửa `.env`.

### 3. Khởi tạo bảng

```powershell
.\.venv\Scripts\python.exe backend\init_db.py
```

Script tạo các bảng còn thiếu theo `models.py`; không tạo database, không migrate cấu trúc bảng đã tồn tại. Sáu bảng: `giao_vien`, `de_thi`, `cau_hoi`, `phong_thi`, `hoc_sinh`, `ket_qua_thi`.

### 4. Chạy dự án bằng hai câu lệnh

Mở **hai terminal** tại thư mục gốc, mỗi terminal chạy một lệnh:

```powershell
.\.venv\Scripts\python.exe -m uvicorn main:app --app-dir backend --reload --port 8000
```

```powershell
npm.cmd --prefix frontend run dev
```

Frontend: <http://localhost:5173>. Backend Swagger: <http://localhost:8000/docs>. Health: <http://localhost:8000/api/health>. Dùng `main:app` để chạy cả REST và Socket.IO. Tắt bằng Ctrl+C ở mỗi terminal.

### 5. Trình diễn luồng sử dụng

1. Giáo viên mở `/register`, đăng ký rồi đăng nhập `/login`.
2. Mở `/exams/create-ai`, nhập tên/khối/số câu, nhập văn bản hoặc chọn `.txt` UTF-8 tối đa 1 MB. Nhấn **Sinh đề bằng AI**, kiểm duyệt rồi **Lưu vào kho đề**.
3. Mở `/rooms/create`, chọn đề, tên lớp và thời gian làm bài. Chia sẻ mã 6 ký tự chữ in hoa và số.
4. Học sinh mở `/join` trong trình duyệt riêng, nhập họ tên và mã phòng. Giáo viên nhấn **Bắt đầu làm bài**.
5. Học sinh chọn đáp án và nộp; hết giờ sẽ tự nộp. Giáo viên theo dõi trạng thái, điểm, xếp hạng; có thể xuất CSV và đóng phòng.

## Kiểm thử và bằng chứng nghiệm thu

Unit/integration backend dùng database fixture; các test UI thông thường giả lập API/Socket để kiểm tra được cả lỗi và race condition:

```powershell
Push-Location backend
..\.venv\Scripts\python.exe -B -m unittest discover -s tests
Pop-Location
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
```

E2E toàn hệ thống sử dụng **Chrome + PostgreSQL + Gemini thật**, không mock HTTP/Socket/AI:

```powershell
.\.venv\Scripts\python.exe -B backend\verify_e2e.py
```

Dừng Vite trên cổng 5173 trước khi chạy E2E. Script tự chạy Vite/backend tạm, tạo schema PostgreSQL `qa_e2e_<uuid>`, gọi Gemini một lần, kiểm tra 8 bước, xuất `docs/qa/`, rồi dọn schema và tiến trình do nó tạo. User database cần quyền tạo schema. Kiểm thử E2E gọi API ngoài và tiêu thụ quota Gemini. `docs/qa/gemini_preview.json` là output thật; `docs/qa/exam_started.json` là payload học sinh không có đáp án đúng.

Kết quả và giới hạn kiểm thử: [QA_REPORT.md](docs/QA_REPORT.md). Hợp đồng REST/Socket và ví dụ JSON: [API_DOCUMENTATION.md](docs/API_DOCUMENTATION.md). Báo cáo đồ án: [COURSE_REPORT.md](docs/COURSE_REPORT.md).

## Khôi phục phiên và giới hạn vận hành

- Chạy **một worker**: membership, lock và broadcast đang ở một process; chưa triển khai Redis để chia sẻ giữa nhiều worker.
- Giữ `backend/.runtime/`: nơi lưu deadline, dấu vết mất kết nối, receipt và chi tiết số câu đúng. PostgreSQL lưu điểm/trạng thái. Xóa runtime có thể khiến khôi phục/xem chi tiết kết quả trả 503, không tự chấm lại hoặc cấp deadline mới.
- Học sinh giữ định danh trong `sessionStorage`, đáp án theo phiên thi trong `localStorage`. F5/kết nối lại cùng phiên giữ bài làm. Tab mới hoặc xóa dữ liệu trình duyệt không có định danh; khôi phục tab đã đóng cần trình duyệt phục hồi `sessionStorage` (ví dụ Restore Closed Tab). Sau kết quả, **Quay về trang chủ** chủ động kết thúc phiên học sinh.
- Khi mất mạng, giao diện khóa đáp án và đóng băng hiển thị thời gian; deadline phòng vẫn chạy. Khôi phục trước deadline chuyển về `DANG_THI`; không cộng thêm thời gian.
- Bài nộp đầu tiên được giữ nguyên khi retry. Bài nộp đến muộn do offline hiện được chấp nhận và đánh dấu `receivedLate`; đóng phòng chặn bài nộp mới, không tự chấm bài chưa nộp.
- Tham gia ẩn danh hiện kiểm tra cặp ID học sinh/phòng; chưa có admission token riêng, chống gian lận hay phục hồi danh tính trên thiết bị khác.
- Responsive được kiểm chứng bằng Chrome mô phỏng viewport/touch, chưa kiểm thử thiết bị Android/iOS vật lý.

## Khắc phục lỗi thường gặp

| Lỗi | Cách xử lý |
| --- | --- |
| Backend từ chối khởi động vì JWT | Đặt `JWT_SECRET_KEY` riêng dài ít nhất 32 byte. |
| Database không kết nối | Kiểm tra PostgreSQL, database, quyền user và `DATABASE_URL`; biến hệ điều hành có thể ghi đè `.env`. |
| AI báo 503/timeout | Kiểm tra API key, model, mạng, quota; yêu cầu quá 15 giây sẽ bị hủy. Văn bản được giữ để thử lại. |
| AI trả 400 | JSON/câu hỏi không đúng hợp đồng; đề chưa được lưu, có thể sinh lại. |
| Request 422 | Kiểm tra tên field, trường bắt buộc, kiểu số nguyên và mã `[A-Z0-9]{6}` trong Swagger. |
| Không kết nối Socket | Chạy `main:app`, kiểm tra URL backend và cổng; restart Vite sau đổi env. |
| Cổng 5173 đang dùng | Dừng Vite cũ; cấu hình strictPort không tự chuyển cổng. |
| Import `app` khi chạy unittest | Chạy unittest trong thư mục `backend` như lệnh phía trên. |
| Runtime/result 503 | Kiểm tra quyền đọc/ghi và giữ thư mục `.runtime`; không sửa điểm thủ công để che lỗi. |

Các tài liệu ràng buộc: [overview.md](overview.md), [.rules.md](.rules.md), [master_plan.md](master_plan.md). Không chỉnh sửa schema/cascade của `models.py` trong quá trình QA.
