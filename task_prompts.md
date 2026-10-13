# 📑 BỘ PROMPT CHUẨN THỰC THI DỰ ÁN (TASK-BY-TASK PROMPTS)
> **Dự án:** Hệ thống Tạo Đề Thi AI & Tổ chức Thi Trực Tuyến  
> **Vai trò:** Hướng dẫn AI lập trình viên thực thi từng Task độc lập, tuyệt đối không chệch khỏi quy chuẩn kỹ thuật.  
> **Nguyên tắc cốt lõi:** Luôn gắn kèm 3 file neo ngữ cảnh (`@overview.md`, `@.rules.md`, `@master_plan.md`).

---

## 🧭 HƯỚNG DẪN DÙNG PROMPT CHO DEVELOPER
1. Mỗi khi bắt đầu một Task mới, hãy copy toàn bộ nội dung khối prompt tương ứng dưới đây và gửi cho AI.
2. Tuyệt đối không gộp nhiều Task vào 1 lần prompt để tránh tình trạng AI bị quá tải ngữ cảnh và tự ý giản lược code hoặc sinh mã lỗi.
3. Luôn yêu cầu AI cung cấp **Proof of Completion (Bằng chứng nghiệm thu)** trước khi tick hoàn thành Task trong `master_plan.md`.

---

# ==============================================================================
# PHASE 1: NỀN TẢNG, CƠ SỞ DỮ LIỆU & XÁC THỰC (FOUNDATION & AUTH)
# ==============================================================================

## 📌 PROMPT CHO TASK 1.1: Project Initialization (Khởi tạo dự án & Monorepo)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ tuyệt đối 3 tài liệu ràng buộc sau:
- @overview.md (Kiến trúc tổng thể & Tech stack)
- @.rules.md (Quy tắc ranh giới module & Definition of Done)
- @master_plan.md (Lộ trình chi tiết)

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 1.1 (Project Initialization)
Mục tiêu: Chuẩn hóa cấu trúc Monorepo, cấu hình khung sườn Backend (FastAPI) và chuẩn bị cấu trúc thư mục Frontend.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- TUYỆT ĐỐI KHÔNG sửa đổi, xóa hoặc thêm bất kỳ bảng/cột nào trong file `backend/app/models/models.py`.
- KHÔNG làm trước các tính năng của Task 1.2, 1.3 (chưa viết code đăng ký/đăng nhập JWT, chưa làm giao diện).
- Tuân thủ quy ước đặt tên: `snake_case` cho Backend/Database, `camelCase`/`PascalCase` cho Pydantic schemas.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Chuẩn hóa cấu trúc thư mục:
   - `backend/app/api/` (Routers)
   - `backend/app/core/` (Cấu hình DB, settings)
   - `backend/app/models/` (Giữ nguyên `models.py`)
   - `backend/app/schemas/` (Pydantic schemas)
   - `backend/app/services/` (Business logic)
   - `backend/main.py` (FastAPI app entry point)
   - `backend/requirements.txt`
   - `frontend/` (Thư mục dự án React)
2. Viết file `backend/main.py`:
   - Khởi tạo FastAPI instance với title "AI Quiz & Exam API".
   - Cấu hình CORS Middleware hỗ trợ Frontend (`http://localhost:5173`, `http://localhost:3000`), methods=["*"], headers=["*"], credentials=True.
   - Tạo endpoint kiểm tra hệ thống: `GET /api/health` trả về `{"status": "ok", "app": "Quiz AI & Exam System"}`.
3. Tạo file `backend/requirements.txt`:
   - Bao gồm các thư viện: `fastapi`, `uvicorn[standard]`, `sqlalchemy`, `psycopg2-binary`, `pydantic`, `python-dotenv`, `python-jose[cryptography]`, `passlib[bcrypt]`, `python-socketio`, `google-genai`.

### 🔍 PROOF OF COMPLETION (Nghiệm thu theo .rules.md):
- Hiển thị cây thư mục monorepo thực tế.
- Xuất toàn bộ code của `backend/main.py` và `backend/requirements.txt`.
- Cung cấp câu lệnh terminal để khởi chạy uvicorn server ở môi trường local.
```

---

## 📌 PROMPT CHO TASK 1.2: Database Implementation (Kiểm tra & Khởi tạo Database)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 1.2 (Database Implementation)
Mục tiêu: Đảm bảo kết nối tới PostgreSQL và chạy lệnh khởi tạo toàn bộ các bảng dữ liệu từ `models.py` một cách an toàn.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- TUYỆT ĐỐI KHÔNG chỉnh sửa định nghĩa các trường dữ liệu, khóa chính, khóa ngoại hay enum trong `backend/app/models/models.py`.
- KHÔNG viết code API hay logic nghiệp vụ trong task này.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Rà soát file `backend/app/core/database.py`:
   - Đảm bảo cơ chế đọc chuỗi kết nối an toàn (sử dụng biến môi trường từ `.env` nếu có, fallback về cấu hình mặc định).
   - Cung cấp hàm dependency `get_db()` sử dụng `yield` để cung cấp DB session cho FastAPI routes theo đúng mục II của `.rules.md`.
2. Kiểm tra và hoàn thiện script `backend/init_db.py`:
   - Khởi tạo tất cả các bảng thông qua `Base.metadata.create_all(bind=engine)`.
   - Bổ sung khối try-except để bắt lỗi kết nối tới PostgreSQL và in hướng dẫn rõ ràng nếu người dùng chưa bật PostgreSQL service.

### 🔍 PROOF OF COMPLETION (Nghiệm thu theo .rules.md):
- Schema Verification Proof: Liệt kê danh sách 6 bảng (`giao_vien`, `de_thi`, `cau_hoi`, `phong_thi`, `hoc_sinh`, `ket_qua_thi`) và 2 Enums (`TrangThaiBaiLam`, `TrangThaiPhongThi`) đã được ánh xạ chuẩn xác.
- File code hoàn chỉnh của `backend/app/core/database.py` và `backend/init_db.py`.
- Câu lệnh chạy `python backend/init_db.py`.
```

---

## 📌 PROMPT CHO TASK 1.3: Teacher Authentication Backend (Xác thực Giáo viên)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 1.3 (Teacher Authentication - Backend)
Mục tiêu: Xây dựng hệ thống Đăng ký, Đăng nhập và Xác thực JWT dành riêng cho Giáo viên (`GiaoVien`).

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- TUYỆT ĐỐI KHÔNG lưu mật khẩu dạng plain text. Bắt buộc băm mật khẩu bằng `passlib` (bcrypt).
- Tuyệt đối không nhồi nhét logic băm mật khẩu và tạo JWT trực tiếp trong Router; phải tách ra `core/security.py` và `services/auth_service.py`.
- Tuân thủ ERR_03: Mọi dữ liệu vào phải kiểm tra qua Pydantic schema v2, trả về 422 nếu sai format.
- TUYỆT ĐỐI KHÔNG sửa đổi bảng `giao_vien` trong `models.py`.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Tạo module bảo mật `backend/app/core/security.py`:
   - Hàm `hash_password(password: str) -> str`
   - Hàm `verify_password(plain_password: str, hashed_password: str) -> bool`
   - Hàm `create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> str`
   - Dependency `get_current_teacher(token: str = Depends(...), db: Session = Depends(get_db))` giải mã JWT và lấy thông tin giáo viên.
2. Tạo Pydantic Schemas trong `backend/app/schemas/auth.py`:
   - `TeacherRegisterSchema`: email, mat_khau, ho_ten, truong, tinh.
   - `TeacherLoginSchema`: email, mat_khau.
   - `TeacherResponseSchema`: id_gv, email, ho_ten, truong, tinh (ẨN HOÀN TOÀN `mat_khau`).
   - `TokenSchema`: access_token, token_type.
3. Tạo Router trong `backend/app/api/auth.py`:
   - `POST /api/auth/register`: Đăng ký tài khoản giáo viên (kiểm tra trùng email, băm mật khẩu, lưu DB).
   - `POST /api/auth/login`: Đăng nhập, trả về JWT Access Token.
   - `GET /api/auth/me`: Lấy thông tin giáo viên hiện tại từ token.
4. Đăng ký router vào `backend/main.py`.

### 🔍 PROOF OF COMPLETION (Nghiệm thu theo .rules.md):
- Schema Verification Proof: Code đầy đủ của `backend/app/schemas/auth.py` chứng minh không để lộ trường `mat_khau` ở response.
- Transaction Safety Proof: Khối `try-except` và `db.rollback()` khi thực hiện lưu giáo viên mới vào DB.
- Mẫu cURL hoặc JSON payload mẫu kiểm thử thành công cho `register` và `login`.
```

---

## 📌 PROMPT CHO TASK 1.4: UI Foundation & Auth Frontend (Giao diện Xác thực)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 1.4 (UI Foundation & Teacher Auth - Frontend)
Mục tiêu: Dựng khung giao diện ReactJS + TailwindCSS, cấu hình định tuyến (Routing) và màn hình Đăng ký / Đăng nhập cho Giáo viên.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- Chỉ làm phần Auth của Giáo viên và khung điều hướng. Chưa xây dựng logic phòng thi hoặc làm bài của học sinh.
- Lưu trữ JWT an toàn trong `localStorage` hoặc Cookie, có thiết lập Axios/Fetch Interceptor tự động gắn Header `Authorization: Bearer <token>`.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Cấu hình Frontend cơ bản:
   - Thiết lập TailwindCSS, React Router v6.
   - Tạo instance API client (Axios/Fetch) cấu hình `baseURL` trỏ về Backend FastAPI.
2. Xây dựng Auth Context / Store:
   - Quản lý trạng thái đăng nhập, hàm `login()`, `logout()`, `register()`.
   - Tạo `ProtectedRoute` để chặn người chưa đăng nhập truy cập Teacher Dashboard.
3. Xây dựng UI:
   - Trang Đăng nhập (`/login`): Form Email, Password, thông báo lỗi nếu sai tài khoản.
   - Trang Đăng ký (`/register`): Form đầy đủ Họ tên, Email, Mật khẩu, Trường, Tỉnh.
   - Khung Teacher Layout cơ bản (`/dashboard`): Header có thông tin giáo viên và nút Đăng xuất.

### 🔍 PROOF OF COMPLETION:
- Danh sách các component và route đã tạo trong `frontend/`.
- Code của component `ProtectedRoute.jsx` và API service gọi backend `/api/auth/login`.
- Hướng dẫn câu lệnh chạy Frontend (`npm run dev`).
```

---

# ==============================================================================
# PHASE 2: TÍCH HỢP AI & QUẢN LÝ KHO ĐỀ THI (AI & EXAM CRUD)
# ==============================================================================

## 📌 PROMPT CHO TASK 2.1: AI Service Integration (Tích hợp Gemini Service)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md (Đặc biệt: Mục I.3 Isolation AI, Mục III ERR_01, Mục IV Definition of Done)
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 2.1 (Gemini AI Service Integration)
Mục tiêu: Xây dựng service cô lập giao tiếp với Gemini API để đọc văn bản thô và sinh danh sách câu hỏi trắc nghiệm dưới dạng JSON chuẩn.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- BẮT BUỘC đặt toàn bộ logic AI trong file `backend/app/services/gemini_service.py`. Tuyệt đối không gọi Gemini trực tiếp trong Router.
- BẮT BUỘC tuân thủ hợp đồng lỗi **ERR_01**: Nếu quá trình gọi AI quá 15 giây (timeout) hoặc trả về JSON sai cú pháp, phải raise ngoại lệ để tầng Router rollback DB và trả về HTTP 503/400. Tuyệt đối không lưu bản ghi rác.
- Đầu ra của hàm AI BẮT BUỘC phải là JSON Array, mỗi phần tử có đúng các key khớp bảng `CauHoi`:
  `noi_dung`, `dap_an_a`, `dap_an_b`, `dap_an_c`, `dap_an_d`, `dap_an_dung` (chỉ nhận giá trị 'A', 'B', 'C', hoặc 'D').

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN
1. Xây dựng `backend/app/services/gemini_service.py`:
   - Hàm `generate_quiz_from_text(raw_text: str, num_questions: int = 10) -> List[dict]`
   - Viết System Prompt chặt chẽ ép Gemini trả về JSON thuần túy (dùng Structured Outputs hoặc `response_mime_type="application/json"`).
   - Thiết lập cơ chế timeout (15 giây).
   - Hàm validate dữ liệu JSON trả về để đảm bảo đủ 4 phương án và đáp án đúng hợp lệ ('A'|'B'|'C'|'D').

### 🔍 PROOF OF COMPLETION (Nghiệm thu theo .rules.md):
- Code hoàn chỉnh của `backend/app/services/gemini_service.py`.
- Đoạn mã chứng minh xử lý lỗi timeout 15s và ngoại lệ JSON (khớp quy tắc ERR_01).
- Mẫu JSON output thực tế do hàm trả về từ một đoạn văn bản test ngắn.
```

---

## 📌 PROMPT CHO TASK 2.2: Exam & Question CRUD APIs (Backend Đề thi & Câu hỏi)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md (Mục I.1 Data Integrity Cascade, Mục I.2 Core API Layer, Mục IV Transaction Safety)
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 2.2 (Exam APIs Backend)
Mục tiêu: Xây dựng các API CRUD cho Đề thi (`DeThi`) và Câu hỏi (`CauHoi`), tích hợp gọi `gemini_service` để tạo đề thi tự động.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- TUYỆT ĐỐI KHÔNG vi phạm quy tắc Transaction: Một đề thi cùng N câu hỏi phải được lưu trong CÙNG 1 TRANSACTION. Nếu 1 câu hỏi lỗi, phải `db.rollback()` toàn bộ (không được để lại đề thi trống).
- Giữ nguyên vẹn quan hệ bao hàm (`cascade="all, delete-orphan"`): Khi xóa 1 đề thi thì toàn bộ câu hỏi liên thuộc phải tự động bị xóa sạch.
- Router chỉ nhận request, gọi Service và trả về qua Pydantic schema v2.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Tạo Schemas `backend/app/schemas/exam.py`:
   - `CauHoiCreateSchema`, `CauHoiResponseSchema`
   - `DeThiCreateManualSchema` (tạo thủ công)
   - `DeThiGenerateAISchema` (gửi raw text, số lượng câu hỏi, tên đề thi, khối lớp)
   - `DeThiDetailResponseSchema` (chứa danh sách câu hỏi)
   - `DeThiListResponseSchema` (danh sách tóm tắt đề thi của giáo viên)
2. Tạo Service `backend/app/services/exam_service.py`:
   - Xử lý logic tạo đề từ AI: gọi `gemini_service`, tạo đối tượng `DeThi`, tạo danh sách `CauHoi`, commit DB trong khối `try-except`.
   - Logic xóa đề thi (kiểm tra quyền sở hữu của giáo viên).
3. Tạo Router `backend/app/api/exam.py`:
   - `POST /api/exams/generate-ai`: Tạo đề thi bằng AI.
   - `GET /api/exams`: Lấy danh sách đề thi của giáo viên hiện tại.
   - `GET /api/exams/{id_de_thi}`: Lấy chi tiết đề thi và câu hỏi.
   - `PUT /api/exams/{id_de_thi}`: Chỉnh sửa đề thi / câu hỏi.
   - `DELETE /api/exams/{id_de_thi}`: Xóa đề thi (kèm cascade xóa câu hỏi).

### 🔍 PROOF OF COMPLETION (Nghiệm thu theo .rules.md):
- Transaction Safety Proof: Chỉ rõ vị trí mã nguồn `try-except` và `db.rollback()` khi tạo đồng thời DeThi + N CauHoi.
- Schema Verification Proof: File `schemas/exam.py` khớp 100% với kiểu dữ liệu của `DeThi` và `CauHoi` trong `models.py`.
- JSON Response mẫu khi gọi `POST /api/exams/generate-ai` thành công.
```

---

## 📌 PROMPT CHO TASK 2.3: Exam Dashboard Frontend (Giao diện Quản lý Đề thi)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 2.3 (Exam Dashboard - Frontend)
Mục tiêu: Xây dựng giao diện cho Giáo viên để nhập tài liệu/văn bản sinh câu hỏi AI, xem trước (review/edit) câu hỏi và quản lý danh sách đề thi.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- Chỉ tập trung vào Module Quản lý đề thi của Giáo viên. Chưa triển khai tạo Phòng thi (Task 3.x).
- Giao diện phải có xử lý trạng thái Loading (Spinner/Skeleton) khi chờ AI sinh câu hỏi và xử lý thông báo lỗi (ERR_01: Timeout).

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Xây dựng trang "Tạo đề thi bằng AI" (`/exams/create-ai`):
   - Ô nhập tên đề, chọn khối lớp, số lượng câu hỏi.
   - Vùng nhập văn bản tài liệu thô (Textarea) hoặc upload file text.
   - Nút "Sinh đề bằng AI" có loading indicator.
2. Xây dựng màn hình "Kiểm duyệt & Chỉnh sửa đề thi":
   - Hiển thị danh sách câu hỏi AI trả về với 4 đáp án A, B, C, D.
   - Cho phép giáo viên sửa nội dung, thay đổi đáp án đúng trước khi bấm "Lưu vào kho đề".
3. Xây dựng trang "Kho đề thi cá nhân" (`/exams`):
   - Hiển thị danh sách đề thi dạng card/table (Tên đề, số câu, ngày tạo).
   - Nút Xem chi tiết, Sửa, Xóa đề thi.

### 🔍 PROOF OF COMPLETION:
- Danh sách các component giao diện đã tạo.
- Code component xử lý preview và chỉnh sửa câu hỏi trước khi lưu.
- Ảnh chụp màn hình hoặc mô tả luồng UI từ lúc nhập văn bản -> loading -> xem trước câu hỏi.
```

---

# ==============================================================================
# PHASE 3: QUẢN LÝ PHÒNG THI & HỌC SINH VÀO PHÒNG (ROOM & STUDENT ENTRY)
# ==============================================================================

## 📌 PROMPT CHO TASK 3.1: Room APIs Backend (Tạo Phòng & Học Sinh Gia Nhập)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md (Mục I.1 Data Integrity, Mục III ERR_03, Mục IV Definition of Done)
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 3.1 (Room APIs - Backend)
Mục tiêu: Xây dựng các API cho Giáo viên tạo phòng thi (sinh mã code 6 ký tự) và API cho Học sinh tham gia phòng thi ẩn danh.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- Mã phòng `ma_code_phong_thi` BẮT BUỘC sinh tự động 6 ký tự ngẫu nhiên (chữ in hoa + số), đảm bảo UNIQUE.
- Học sinh tham gia hoàn toàn ẨN DANH (chỉ cần Họ tên + Mã phòng), không yêu cầu tài khoản hay password.
- Khi học sinh vào phòng thành công, phải tự động tạo bản ghi `HocSinh` và `KetQuaThi` với trạng thái ban đầu `DANG_THI` và điểm mặc định 0.0 (theo đúng Class Diagram).
- Trạng thái ban đầu của phòng thi BẮT BUỘC là `TrangThaiPhongThi.DANG_CHO`.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Tạo Schemas `backend/app/schemas/room.py`:
   - `RoomCreateSchema`: id_de_thi, ten_lop, thoi_gian_lam_bai (số nguyên phút).
   - `RoomResponseSchema`: id_phong_thi, ma_code_phong_thi, ten_lop, thoi_gian_lam_bai, trang_thai_phong, ngay_tao_phong.
   - `StudentJoinSchema`: ho_va_ten, ma_code_phong_thi.
   - `StudentJoinResponseSchema`: id_hs, ho_va_ten, ma_code_phong_thi, id_phong_thi, ten_lop, thoi_gian_lam_bai.
2. Xây dựng `backend/app/services/room_service.py`:
   - Hàm sinh mã code 6 ký tự ngẫu nhiên không trùng lặp.
   - Hàm tạo phòng thi cho giáo viên.
   - Hàm xử lý học sinh join phòng: kiểm tra mã code tồn tại, kiểm tra phòng không ở trạng thái `DA_DONG`, tạo `HocSinh` và `KetQuaThi`.
3. Xây dựng Router `backend/app/api/room.py`:
   - `POST /api/rooms`: Giáo viên tạo phòng thi mới.
   - `GET /api/rooms/{id_phong_thi}`: Lấy thông tin phòng thi và danh sách thí sinh đã vào.
   - `POST /api/rooms/join`: Học sinh nhập Họ tên + Mã code để vào phòng.

### 🔍 PROOF OF COMPLETION (Nghiệm thu theo .rules.md):
- Schema Verification Proof: Code `schemas/room.py` khớp 100% với `models.PhongThi` và `models.HocSinh`.
- State Output Proof: JSON response mẫu khi tạo phòng cho thấy trường `trang_thai_phong` là `"DANG_CHO"`.
- Transaction Safety Proof: Khối `try-except` và `db.rollback()` khi tạo đồng thời `HocSinh` và `KetQuaThi`.
```

---

## 📌 PROMPT CHO TASK 3.2: Teacher Room UI Frontend (Giao diện Phòng thi Giáo viên)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 3.2 (Teacher Room UI - Frontend)
Mục tiêu: Xây dựng giao diện cho Giáo viên để tạo phòng thi từ một đề thi có sẵn và hiển thị màn hình phòng chờ (Waiting Room).

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Modal / Màn hình "Khởi tạo Phòng thi":
   - Cho phép chọn đề thi từ kho đề, nhập Tên lớp/nhóm, nhập Thời gian làm bài (phút).
   - Bấm "Tạo phòng" -> gọi API nhận mã PIN 6 ký tự.
2. Màn hình Phòng chờ của Giáo viên (`/rooms/:id/waiting`):
   - Hiển thị nổi bật mã code phòng thi 6 ký tự với kích thước lớn để học sinh dễ nhìn.
   - Hiển thị danh sách học sinh đang vào phòng theo thời gian thực (tạm thời polling hoặc chuẩn bị sẵn slot cho Socket.io).
   - Nút hành động lớn "Bắt đầu làm bài" (chuẩn bị kích hoạt sang Task 4.x).

### 🔍 PROOF OF COMPLETION:
- Code component `TeacherWaitingRoom.jsx`.
- Giao diện hiển thị rõ mã PIN 6 chữ số và danh sách thí sinh đã tham gia.
```

---

## 📌 PROMPT CHO TASK 3.3: Student Entry UI Frontend (Giao diện Học sinh vào phòng)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 3.3 (Student Entry UI - Frontend)
Mục tiêu: Xây dựng trang đích công khai (Public Landing Page) cho Học sinh nhập Họ tên và Mã PIN phòng thi, cùng màn hình chờ thi.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- Giao diện học sinh KHÔNG yêu cầu đăng nhập tài khoản.
- Lưu session học sinh (`id_hs`, `ma_code_phong_thi`, `ho_va_ten`) vào `sessionStorage` để phục vụ cơ chế khôi phục khi reload trang.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Trang chủ / Landing Page Học sinh (`/` hoặc `/join`):
   - Form đơn giản, thân thiện trên mobile: Nhập Họ và tên + Nhập mã phòng 6 ký tự.
   - Nút "Vào phòng thi".
   - Bắt lỗi nếu mã phòng không tồn tại hoặc phòng đã đóng.
2. Màn hình Chờ làm bài của Học sinh (`/exam-room/:code/waiting`):
   - Hiển thị thông tin: Tên thí sinh, Tên lớp, Thời gian làm bài.
   - Trạng thái thông báo: "Đang đợi giáo viên bắt đầu bài thi...".
   - Hiệu ứng animation chờ đợi (Pulse / Lottie loader).

### 🔍 PROOF OF COMPLETION:
- Code của component `StudentJoin.jsx` và `StudentWaiting.jsx`.
- Đảm bảo tính đáp ứng (Responsive) tốt trên màn hình điện thoại (Tailwind mobile-first).
```

---

# ==============================================================================
# PHASE 4: THỰC THI THỜI GIAN THỰC & QUẢN LÝ TRẠNG THÁI (SOCKET.IO)
# ==============================================================================

## 📌 PROMPT CHO TASK 4.1: Socket.io Setup (Hạ tầng Real-time Backend & Frontend)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md (Mục I.4 Real-time Module, Mục II Communication Protocols)
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 4.1 (Socket.io Setup)
Mục tiêu: Thiết lập hạ tầng kết nối WebSocket hai chiều bằng `python-socketio` ở Backend và `socket.io-client` ở Frontend.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- TUYỆT ĐỐI KHÔNG gọi các truy vấn Database quét diện rộng (Select All) bên trong các event loop của Socket để tránh nghẽn luồng (theo mục I.4 của `.rules.md`).
- Bắt buộc chia phòng bằng Room ID của Socket.io (`join_room` theo `ma_code_phong_thi`).

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Cấu hình Socket.io Backend (`backend/app/core/socket_manager.py` hoặc gắn vào FastAPI ASGI app):
   - Khởi tạo `socketio.AsyncServer(async_mode='asgi', cors_allowed_origins='*')`.
   - Bọc ASGIApp tích hợp vào FastAPI trong `main.py`.
   - Xử lý sự kiện cơ bản: `connect`, `disconnect`.
   - Event `join_exam_room`: Nhận `ma_code_phong_thi`, `user_type` ('teacher' | 'student'), `id_hs`, đưa socket vào room tương ứng và broadcast cho giáo viên danh sách học sinh vừa join.
2. Cấu hình Socket.io Frontend (`frontend/src/services/socket.js`):
   - Khởi tạo socket singleton kết nối tới URL backend.
   - Cung cấp hook hoặc helper để join room và lắng nghe event.

### 🔍 PROOF OF COMPLETION (Nghiệm thu theo .rules.md):
- Code khởi tạo Socket server trên FastAPI và Socket client trên React.
- Log minh chứng 1 client kết nối thành công tới socket server và join vào room `ma_code_phong_thi`.
```

---

## 📌 PROMPT CHO TASK 4.2: Real-time Exam Flow (Luồng Làm Bài & Đồng Bộ Đếm Ngược)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 4.2 (Real-time Exam Flow & Countdown)
Mục tiêu: Triển khai luồng thi trực tiếp: Giáo viên bấm "Bắt đầu" -> Broadcast đề thi tới học sinh -> Đồng hồ đếm ngược chạy đồng bộ.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- BẢO MẬT: Đề thi gửi tới Frontend học sinh TUYỆT ĐỐI KHÔNG ĐƯỢC CHỨA trường `dap_an_dung`.
- Khi bắt đầu thi, trạng thái phòng thi BẮT BUỘC cập nhật từ `DANG_CHO` sang `DANG_THI`.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Backend Socket Events:
   - Event `start_exam`: Giáo viên phát lệnh bắt đầu. Backend cập nhật `PhongThi.trang_thai_phong = TrangThaiPhongThi.DANG_THI` trong DB.
   - Lấy danh sách câu hỏi của đề thi, **LỌC BỎ CỘT `dap_an_dung`**, broadcast event `exam_started` kèm danh sách câu hỏi và thời gian thi về tất cả học sinh trong phòng.
2. Frontend Học sinh (Màn hình Làm bài thi `/exam-room/:code/taking`):
   - Nhận event `exam_started`, tự động chuyển sang giao diện làm bài.
   - Hiển thị đồng hồ đếm ngược chính xác (phút:giây).
   - Hiển thị từng câu hỏi với 4 lựa chọn Radio Button A, B, C, D. Lưu tạm đáp án đang chọn vào state/localStorage.
   - Nút "Nộp bài". Tự động kích hoạt nộp bài khi đồng hồ về 00:00.
3. Frontend Giáo viên (Live Tracking Dashboard):
   - Hiển thị trạng thái phòng thi đang là "ĐANG THI".
   - Hiển thị đồng hồ đếm ngược đồng bộ.

### 🔍 PROOF OF COMPLETION (Nghiệm thu theo .rules.md):
- State Output Proof: In JSON response/socket payload chứng minh `trang_thai_phong` chuyển thành `"DANG_THI"`.
- Code chứng minh payload câu hỏi gửi tới học sinh đã loại bỏ hoàn toàn trường `dap_an_dung`.
```

---

## 📌 PROMPT CHO TASK 4.3: Disconnect Handling & Recovery (Xử lý Mất Kết Nối & Khôi Phục)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md (Đặc biệt: Mục III ERR_02 Học sinh ngắt kết nối đột ngột)
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 4.3 (Disconnect Handling & State Recovery)
Mục tiêu: Thực thi cơ chế chống rớt mạng: Phát hiện học sinh disconnect, đóng băng trạng thái bài làm và cho phép khôi phục khi kết nối lại.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- BẮT BUỘC tuân thủ hợp đồng lỗi **ERR_02**:
  - Khi học sinh ngắt kết nối (sự kiện `disconnect` của Socket.io), phát hiện `id_hs`.
  - Cập nhật tức thì bảng `KetQuaThi`, set `trang_thai = 'MAT_KET_NOI'`.
  - Thông báo thời gian thực về màn hình Giáo viên để giáo viên biết thí sinh đang gặp sự cố mạng.
- Khi học sinh tải lại trang (F5) hoặc kết nối lại:
  - Nếu bài thi chưa hết giờ, khôi phục lại trạng thái làm bài, chuyển lại trạng thái về `DANG_THI`.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Backend Socket Handler:
   - Lưu trữ bản đồ kết nối `socket_id <-> id_hs`.
   - Trong event `disconnect`: Tìm `id_hs`, cập nhật DB `KetQuaThi.trang_thai = TrangThaiBaiLam.MAT_KET_NOI`. Broadcast event `student_disconnected` tới phòng thi của giáo viên.
   - Event `reconnect_exam`: Học sinh gửi lại `id_hs` và `ma_code_phong_thi`. Kiểm tra phòng còn thời gian, cập nhật `trang_thai = DANG_THI`, trả lại thời gian còn lại và danh sách câu hỏi.
2. Frontend Giáo viên:
   - Đổi màu thẻ học sinh trên màn hình giám sát sang màu Vàng/Cam kèm nhãn "MẤT KẾT NỐI".
3. Frontend Học sinh:
   - Cơ chế tự động gọi `reconnect_exam` khi socket connect lại.

### 🔍 PROOF OF COMPLETION (Nghiệm thu theo .rules.md):
- Code xử lý event `disconnect` thể hiện rõ việc cập nhật `trang_thai = 'MAT_KET_NOI'` vào bảng `KetQuaThi`.
- State Output Proof: Minh chứng payload broadcast tới giáo viên khi một thí sinh bị rớt mạng.
```

---

# ==============================================================================
# PHASE 5: TỰ ĐỘNG CHẤM ĐIỂM, BẢNG ĐIỂM & HOÀN THIỆN (GRADING & POLISH)
# ==============================================================================

## 📌 PROMPT CHO TASK 5.1: Submission & Auto-Grading (Nộp Bài & Tự Động Chấm Điểm)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md (Mục I.2 Core API Layer, Mục IV Transaction Safety)
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 5.1 (Submission & Auto-Grading - Backend)
Mục tiêu: Xây dựng API tiếp nhận bài làm của học sinh, so khớp với đáp án đúng, tự động tính điểm theo thang điểm 10 và cập nhật trạng thái `HOAN_THANH`.

### ⛔ CÁC ĐIỀU CẤM & RANH GIỚI NGHIÊM NGẶT:
- Tuyệt đối không nhồi nhét công thức tính điểm trực tiếp trong Router; phải đặt trong `grading_service.py`.
- Logic tính điểm: `diem_so = round((so_cau_dung / tong_so_cau) * 10, 2)`.
- Khi nộp bài thành công: Cập nhật `KetQuaThi.diem_so = diem_so`, `KetQuaThi.trang_thai = TrangThaiBaiLam.HOAN_THANH`.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Schemas `backend/app/schemas/submission.py`:
   - `AnswerItem`: id_cau_hoi, dap_an_chon ('A' | 'B' | 'C' | 'D' | null).
   - `ExamSubmissionSchema`: id_hs, id_phong_thi, danh_sach_cau_tra_loi: List[AnswerItem].
   - `SubmissionResultResponseSchema`: id_hs, diem_so, so_cau_dung, tong_so_cau, trang_thai.
2. Service `backend/app/services/grading_service.py`:
   - Truy vấn danh sách đáp án đúng từ bảng `CauHoi` theo `id_de_thi` của phòng thi.
   - So khớp và tính điểm.
   - Cập nhật bản ghi `KetQuaThi` trong khối transaction an toàn (`try-except`, `db.rollback()`).
   - Kích hoạt broadcast qua Socket event `student_submitted` báo điểm về cho Giáo viên.
3. Router `backend/app/api/submission.py`:
   - `POST /api/submissions/submit`: Tiếp nhận bài thi và trả về kết quả điểm số cho học sinh.

### 🔍 PROOF OF COMPLETION (Nghiệm thu theo .rules.md):
- Schema Verification Proof: Code `schemas/submission.py` chuẩn Pydantic v2.
- Transaction Safety Proof: Khối `try-except` và `db.rollback()` khi lưu điểm thi vào `KetQuaThi`.
- State Output Proof: Minh chứng trạng thái `trang_thai` của `KetQuaThi` đã cập nhật thành `"HOAN_THANH"` kèm `diem_so`.
```

---

## 📌 PROMPT CHO TASK 5.2: Live Leaderboard (Bảng Điểm Thời Gian Thực)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 5.2 (Live Leaderboard & Result View - Frontend)
Mục tiêu: Xây dựng màn hình xem điểm ngay lập tức cho học sinh sau khi nộp bài và Bảng xếp hạng trực tiếp tự động nhảy điểm cho Giáo viên.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Frontend Học sinh - Màn hình Kết quả (`/exam-room/:code/result`):
   - Hiển thị điểm số lớn (Vd: 8.5 / 10), số câu đúng (vd: 17/20 câu).
   - Lời khen/động viên tương ứng với mức điểm.
   - Nút quay về trang chủ.
2. Frontend Giáo viên - Bảng điểm trực tiếp (Live Scoreboard):
   - Nhận event Socket `student_submitted`, tự động cập nhật dòng của học sinh đó: trạng thái đổi sang "ĐÃ HOÀN THÀNH" (Xanh lá) kèm điểm số thực tế.
   - Bảng xếp hạng tự động sắp xếp theo điểm số từ cao xuống thấp.
   - Thống kê tóm tắt: Tổng số thí sinh, số người đã nộp, điểm trung bình của phòng thi.
   - Nút "Đóng phòng thi" (`DA_DONG`) và xuất file kết quả (nếu có).

### 🔍 PROOF OF COMPLETION:
- Code component `StudentResult.jsx` và `TeacherLiveLeaderboard.jsx`.
- Code xử lý cập nhật realtime trên danh sách học sinh khi nhận được socket event `student_submitted`.
```

---

## 📌 PROMPT CHO TASK 5.3: QA, UI Polish & Final Report (Kiểm Thử Toàn Diện & Hoàn Thiện)

```markdown
Bạn là AI lập trình viên thực thi dự án. Hãy đọc kỹ và tuân thủ:
- @overview.md
- @.rules.md
- @master_plan.md

### 🎯 NHIỆM VỤ HIỆN TẠI: Thực hiện duy nhất Task 5.3 (QA, UI Polish & Course Finalization)
Mục tiêu: Kiểm thử luồng khép kín (End-to-End Test), tối ưu giao diện trên thiết bị di động và chuẩn bị tài liệu báo cáo đồ án.

### 🔨 CÔNG VIỆC CẦN THỰC HIỆN:
1. Kiểm toán toàn diện hệ thống (End-to-End Checklist):
   - [x] Đăng ký/Đăng nhập giáo viên -> Nhận JWT.
   - [x] Upload văn bản -> Gemini API sinh câu hỏi JSON -> Lưu thành công vào DB.
   - [x] Tạo phòng thi -> Sinh mã PIN 6 ký tự.
   - [x] Học sinh vào phòng bằng điện thoại/tab ẩn danh -> Nhập mã PIN.
   - [x] Giáo viên bấm Start -> Học sinh nhận câu hỏi (không lộ đáp án đúng) -> Countdown chạy.
   - [x] Thử nghiệm tắt tab học sinh -> Kiểm tra trạng thái `MAT_KET_NOI` trên màn hình giáo viên.
   - [x] Mở lại tab -> Khôi phục bài làm.
   - [x] Học sinh nộp bài -> Chấm điểm đúng theo thang điểm 10 -> Cập nhật lên Live Leaderboard của giáo viên.
2. Rà soát UI/UX:
   - Kiểm tra hiển thị responsive (TailwindCSS) trên màn hình mobile cho toàn bộ các trang của học sinh.
   - Dọn dẹp console.log thừa, xử lý triệt để các cảnh báo React key hoặc hydration warning.
3. Tạo tài liệu `docs/API_DOCUMENTATION.md` và cập nhật `README.md` hướng dẫn chạy dự án bằng 2 câu lệnh.

### 🔍 PROOF OF COMPLETION:
- Bảng tổng kết kết quả kiểm thử toàn bộ 8 bước E2E flow.
- File `README.md` hoàn chỉnh với hướng dẫn setup từ A-Z.
```

---

# 📊 BẢNG THEO DÕI TIẾN ĐỘ THỰC THI (CHECKLIST DÀNH CHO DEVELOPER)

| Task ID | Nội dung | File Prompt | Trạng thái |
| :---: | :--- | :---: | :---: |
| **Task 1.1** | Project Initialization & Monorepo Setup | [Xem Task 1.1](#-prompt-cho-task-11-project-initialization-khởi-tạo-dự-án--monorepo) | ✅ Hoàn thành |
| **Task 1.2** | Database Implementation & Tables | [Xem Task 1.2](#-prompt-cho-task-12-database-implementation-kiểm-tra--khởi-tạo-database) | ✅ Hoàn thành |
| **Task 1.3** | Teacher Authentication (Backend - JWT) | [Xem Task 1.3](#-prompt-cho-task-13-teacher-authentication-backend-xác-thực-giáo-viên) | ✅ Hoàn thành |
| **Task 1.4** | UI Foundation & Auth (Frontend) | [Xem Task 1.4](#-prompt-cho-task-14-ui-foundation--auth-frontend-giao-diện-xác-thực) | ✅ Hoàn thành |
| **Task 2.1** | Gemini AI Service Integration | [Xem Task 2.1](#-prompt-cho-task-21-ai-service-integration-tích-hợp-gemini-service) | ✅ Hoàn thành |
| **Task 2.2** | Exam & Question CRUD APIs | [Xem Task 2.2](#-prompt-cho-task-22-exam--question-crud-apis-backend-đề-thi--câu-hỏi) | ✅ Hoàn thành |
| **Task 2.3** | Exam Dashboard (Frontend) | [Xem Task 2.3](#-prompt-cho-task-23-exam-dashboard-frontend-giao-diện-quản-lý-đề-thi) | ✅ Hoàn thành |
| **Task 3.1** | Room APIs & Student Join (Backend) | [Xem Task 3.1](#-prompt-cho-task-31-room-apis-backend-tạo-phòng--học-sinh-gia-nhập) | ✅ Hoàn thành |
| **Task 3.2** | Teacher Waiting Room UI (Frontend) | [Xem Task 3.2](#-prompt-cho-task-32-teacher-room-ui-frontend-giao-diện-phòng-thi-giáo-viên) | ✅ Hoàn thành |
| **Task 3.3** | Student Entry UI (Frontend) | [Xem Task 3.3](#-prompt-cho-task-33-student-entry-ui-frontend-giao-diện-học-sinh-vào-phòng) | ✅ Hoàn thành |
| **Task 4.1** | Socket.io Infrastructure Setup | [Xem Task 4.1](#-prompt-cho-task-41-socketio-setup-hạ-tầng-real-time-backend--frontend) | ✅ Hoàn thành |
| **Task 4.2** | Real-time Exam Flow & Countdown | [Xem Task 4.2](#-prompt-cho-task-42-real-time-exam-flow-luồng-làm-bài--đồng-bộ-đếm-ngược) | ✅ Hoàn thành |
| **Task 4.3** | Disconnect Handling & Recovery | [Xem Task 4.3](#-prompt-cho-task-43-disconnect-handling--recovery-xử-lý-mất-kết-nối--khôi-phục) | ✅ Hoàn thành |
| **Task 5.1** | Submission & Auto-Grading API | [Xem Task 5.1](#-prompt-cho-task-51-submission--auto-grading-nộp-bài--tự-động-chấm-điểm) | ✅ Hoàn thành |
| **Task 5.2** | Live Leaderboard & Result View | [Xem Task 5.2](#-prompt-cho-task-52-live-leaderboard-bảng-điểm-thời-gian-thực) | ✅ Hoàn thành |
| **Task 5.3** | E2E QA, Mobile Polish & Final Report | [Xem Task 5.3](#-prompt-cho-task-53-qa-ui-polish--final-report-kiểm-thử-toàn-diện--hoàn-thiện) | ✅ Hoàn thành |

