# Quiz AI — Frontend

React 18 + Vite 6 + TailwindCSS 3 + React Router v6 + Axios.
Triển khai xác thực giáo viên, quản lý đề/phòng, học sinh vào phòng ẩn danh và luồng thi trực tiếp qua Socket.IO.
Luồng tạo AI, preview/chỉnh sửa/lưu và kho đề được mô tả tại
[`docs/exam_dashboard.md`](../docs/exam_dashboard.md).

## Chạy local

Từ thư mục gốc dự án, trên PowerShell:

```powershell
Set-Location .\frontend
npm.cmd ci
npm.cmd run dev
```

Trên terminal không bị PowerShell execution policy chặn, dùng `npm run dev`.
Mở <http://localhost:5173>. Vite dùng port cố định 5173 để khớp CORS backend.

Trong terminal khác, khởi động FastAPI từ thư mục gốc:

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -m uvicorn main:app --reload --port 8000
```

Backend cần PostgreSQL và JWT key đã cấu hình ở Task 1.3.
API client mặc định trỏ đến `http://localhost:8000`. Để thay đổi, sao chép
`.env.example` thành `.env.local` và đặt `VITE_API_BASE_URL`, sau đó restart Vite.
Socket.IO dùng cùng URL; `VITE_SOCKET_URL` chỉ cần đặt khi dùng origin riêng.
Biến `VITE_*` công khai trong bundle: chỉ đặt API URL, không đặt JWT secret.

## Routes

| Route | Component | Quyền truy cập |
| --- | --- | --- |
| `/login` | `LoginPage` + `AuthLayout` | Chưa đăng nhập |
| `/register` | `RegisterPage` + `AuthLayout` | Chưa đăng nhập |
| `/dashboard` | `DashboardPage` + `TeacherLayout` | Có token và giáo viên đã xác minh |
| `/` và `/join` | `StudentJoin` + `StudentLayout` | Công khai, không cần tài khoản |
| `/exam-room/:code/waiting` | `StudentWaiting` | Session học sinh trong sessionStorage |
| `/exam-room/:code/taking` | `StudentTaking` | Session học sinh đúng phòng và phiên thi đã bắt đầu |
| `/exam-room/:code/result` | `StudentResult` | Session học sinh đúng phòng; kết quả đọc từ backend |
| `/rooms/create` | `RoomCreatePage` | Giáo viên đã xác minh |
| `/rooms/:id/waiting` | `TeacherWaitingRoom` | Giáo viên sở hữu phòng |
| `*` | `NotFoundPage` | Trang 404 |

## Các module và component

```text
src/
├── App.jsx
├── main.jsx
├── index.css
├── components/
│   ├── Brand.jsx
│   ├── FormField.jsx
│   ├── FormMessage.jsx
│   ├── ProtectedRoute.jsx
│   ├── PublicRoute.jsx
│   └── SessionStatus.jsx
├── contexts/
│   └── AuthContext.jsx
├── layouts/
│   ├── AuthLayout.jsx
│   └── TeacherLayout.jsx
├── pages/
│   ├── DashboardPage.jsx
│   ├── LoginPage.jsx
│   ├── NotFoundPage.jsx
│   └── RegisterPage.jsx
├── services/
│   ├── apiClient.js
│   └── authService.js
└── utils/
    ├── authValidation.js
    └── tokenStorage.js
```

## Hợp đồng xác thực

- `AuthProvider` cung cấp `teacher`, `status`, `login()`, `register()`,
  `logout()` và `refreshSession()`.
- Các field/form/frontend dùng camelCase; service ánh xạ sang `mat_khau`,
  `ho_ten`, `truong`, `tinh` khi gửi đúng hợp đồng API hiện có.
- Register thành công chuyển về login, điền email và hiển thị thông báo.
  Trường/tỉnh để trống được gửi dưới dạng `null`, khớp nullable của backend.
- Login lấy token, lưu vào `localStorage` dưới key `quizAi.teacherAccessToken`,
  rồi gọi `/api/auth/me` để lấy và xác minh giáo viên trước khi mở dashboard.
- Chỉ token được lưu trong localStorage; mật khẩu và hồ sơ giáo viên không
  được lưu ở đó. Mật khẩu chỉ nằm trong form và payload gửi tới endpoint auth.
- Request interceptor tự gắn `Authorization: Bearer <token>` cho request
  xác thực. Login/register không gắn token cũ.
- Response interceptor xử lý 401 của request mang token hiện tại: xóa token,
  xóa trạng thái đăng nhập và chuyển về login. Lỗi mạng/503 khi khôi phục
  phiên giữ token và hiển thị nút thử lại hoặc đăng nhập tài khoản khác.
- `ProtectedRoute` đợi `/me` xác minh; không dựa riêng vào việc token tồn tại.
  Người dùng đã đăng nhập mở login/register được chuyển về dashboard.
- Logout xóa token và trạng thái; event storage đồng bộ đăng xuất giữa các
  tab. Các response cũ không khôi phục session sau khi đã logout/đổi phiên.
- Validation email, tên bắt buộc và mật khẩu tối thiểu 8 ký tự/tối đa 72
  byte UTF-8 chạy trước request. Backend vẫn quyết định validation cuối cùng.
- Module quản lý đề thi dùng preview không ghi DB trước khi giáo viên lưu.
  Hai phòng chờ dùng singleton `services/socket.js`; teacher gửi JWT qua auth,
  student gửi ID từ lượt join ẩn danh. Reconnect tự join lại đúng phòng.
  Giáo viên bắt đầu thi qua Socket.IO; học sinh nhận đề công khai và tự chuyển
  sang màn hình làm bài. Hai bên dùng cùng deadline của server; đáp án học sinh
  lưu localStorage. Nộp thủ công/tự động chờ xác nhận server. Backend Task 5.1
  đã chấm điểm; Task 5.2 tự chuyển sang trang kết quả và cập nhật bảng điểm
  giáo viên theo `student_submitted`, khôi phục điểm khi reload, đóng phòng và xuất CSV.
  Chi tiết/ảnh/code: [`docs/leaderboard.md`](../docs/leaderboard.md).
  Chi tiết Socket.IO và log thật: [`docs/socket_setup.md`](../docs/socket_setup.md).
  Luồng thi, payload thật và ảnh: [`docs/exam_flow.md`](../docs/exam_flow.md).
  Khi mất mạng, form/đồng hồ học sinh đóng băng; reconnect/F5 tự gọi
  `reconnect_exam` trước khi tiếp tục. Thẻ học sinh phía giáo viên đổi màu cam
  với nhãn MẤT KẾT NỐI; snapshot cũ không ghi đè trạng thái mới.
  Chi tiết và bằng chứng: [`docs/exam_recovery.md`](../docs/exam_recovery.md).

## Kiểm tra

```powershell
npm.cmd run build
npm.cmd test
```

Playwright sử dụng Chrome đã cài (`channel: chrome`), tự chạy Vite khi cần
và dừng server do test tạo khi xong. Tests mock API theo hợp đồng backend;
không tạo tài khoản hoặc thay đổi dữ liệu PostgreSQL.

Kết quả nghiệm thu auth: **11 browser tests đạt**:
chặn dashboard, login/payload/Bearer header, reload và logout, validation,
register/chuyển login, optional fields thành null, sai mật khẩu, email trùng,
token hết hạn, retry khi server lỗi, logout giữa các tab và responsive.
Đến Task 5.2 đã kiểm chứng **96 ca browser** (11 auth, 13 exam,
17 teacher room, 17 student entry, 5 Socket.IO, 14 exam flow, 7 exam recovery,
12 leaderboard/result). Lượt toàn bộ có 95 ca đạt; ca footer phòng đóng dùng
kỳ vọng cũ đã sửa và chạy lại đạt. Sau thay đổi cuối, 36 ca liên quan chạy lại đạt.
Production build thành công trên mã nguồn cuối.
Ảnh auth desktop/mobile nằm trong `test-results/`;
ảnh exam nằm trong `docs/screenshots/task_2_3/`.

## Dependency audit

`npm audit` hiện báo **9 cảnh báo: 5 high và 4 moderate** từ dependencies
hiện có. Nhóm high liên quan TailwindCSS 3 và các package build/glob;
nhóm moderate gồm parser CSS và React Router v6.

Audit đề xuất nâng major TailwindCSS lên v4 và React Router lên v7. Task này
giữ React Router v6 theo yêu cầu, không chạy `npm audit fix --force`.
Ứng dụng hiện dùng các đích navigation cố định và không có SSR hydration.
Các cảnh báo vẫn cần được theo dõi; build/test đạt không có nghĩa audit sạch.

Tham chiếu báo cáo:
- <https://github.com/advisories/GHSA-vfj7-8cjw-p6xm>
- <https://github.com/advisories/GHSA-rj75-hqrm-r3gf>
- <https://github.com/advisories/GHSA-wrjc-x8rr-h8h6>
- <https://github.com/advisories/GHSA-337j-9hxr-rhxg>
