# Task 3.3 — Student Entry UI

## Routes và mã component

| Route | Component | Chức năng |
| --- | --- | --- |
| `/` và `/join` | `StudentJoin` | Trang công khai nhập họ tên và mã phòng |
| `/exam-room/:code/waiting` | `StudentWaiting` | Thông tin thí sinh, lớp, thời gian và trạng thái chờ |

Code đầy đủ dùng để nghiệm thu:

- [StudentJoin.jsx](../frontend/src/pages/StudentJoin.jsx).
- [StudentWaiting.jsx](../frontend/src/pages/StudentWaiting.jsx).

Các route học sinh dùng `StudentLayout` và `StudentSessionProvider`, đặt ngoài
`ProtectedRoute` và `PublicRoute` dành cho giáo viên. Học sinh không cần đăng nhập,
email, mật khẩu hoặc JWT. Giáo viên đang có phiên đăng nhập cũng có thể mở các
trang này. `StudentLayout` có liên kết riêng đến `/login` cho giáo viên.

## Luồng vào phòng

1. Nhập họ tên và mã gồm đúng sáu ký tự ASCII chữ in hoa/số. Mã nhập chữ thường
   được đổi thành chữ in hoa, tên và mã được trim trước khi gửi.
2. Validation chặn tên trống và mã sai định dạng trước khi gọi API.
3. “Vào phòng thi” khóa form, hiện loading và chỉ gửi một request khi đang chờ.
4. `studentService.js` gọi POST `/api/rooms/join` với hai trường:

```json
{"hoVaTen": "Nguyễn Văn An", "maCodePhongThi": "AB12CD"}
```

Request sử dụng Axios client có sẵn với `skipAuth: true`: không gắn JWT giáo viên
và không kích hoạt logout giáo viên khi API ẩn danh trả 401.

5. HTTP 404 hiển thị mã không tồn tại; HTTP 409 hiển thị phòng đã đóng/không nhận
   thí sinh. Lỗi validation/server/kết nối hiển thị riêng và giữ nguyên input.
   Không tự retry POST hoặc lưu session khi API thất bại.
6. Sau HTTP 201, validate response, lưu session rồi chuyển đến phòng chờ bằng
   mã backend trả về. Không gọi API giáo viên để tải danh sách thí sinh/phòng.

## Session và khôi phục reload

Key: `sessionStorage['quizAi.studentSession']`. Ví dụ dữ liệu đã kiểm chứng:

```json
{
  "idHs": "student-one",
  "maCodePhongThi": "AB12CD",
  "hoVaTen": "Nguyễn Văn An",
  "idPhongThi": "room-one",
  "tenLop": "Lớp 6A",
  "thoiGianLamBai": 30
}
```

Theo `.rules.md`, dữ liệu frontend dùng camelCase. Các trường bắt buộc mà prompt
đề cập bằng tên model được ánh xạ như sau:

| Trường model | Trường session frontend |
| --- | --- |
| `id_hs` | `idHs` |
| `ma_code_phong_thi` | `maCodePhongThi` |
| `ho_va_ten` | `hoVaTen` |

Ba trường metadata còn lại phục vụ hiển thị tên lớp, thời gian và ID phòng khi
reload. ID/tên là string, thời gian là integer phút, mã khớp `[A-Z0-9]{6}`.

`studentSessionStorage.js` whitelist đúng sáu trường response, không sao chép
token hoặc dữ liệu khác. Session học sinh không được ghi vào localStorage.
`StudentSessionContext.jsx` đọc sessionStorage khi khởi tạo, do đó reload màn hình
chờ khôi phục đúng học sinh và không tạo thêm `HocSinh`/`KetQuaThi`.

Một phiên được lưu cho mỗi tab; tham gia phòng khác thay thế phiên đang nhớ trong
tab sau khi API thành công. Quay lại `/join` có liên kết “Tiếp tục phòng chờ”. Gửi
cùng tên/mã với phiên hiện tại cũng sử dụng lại phiên, không POST thêm.

Session JSON hỏng, thiếu metadata hoặc sai kiểu bị loại bỏ. Màn hình chờ thiếu
session hoặc khác mã chuyển về `/join` với thông báo và mã cần nhập; không tự gọi
join. URL chữ thường của đúng phiên được chuyển về mã in hoa chuẩn.

Nếu trình duyệt chặn sessionStorage hoặc hết quota, lượt join đã thành công vẫn
được giữ trong memory, không gửi lại POST; giao diện thông báo rõ phải giữ trang
mở. Trong trường hợp này reload không thể khôi phục session. Đây là giới hạn thực
tế của browser storage; luồng thông thường luôn ghi session trước khi điều hướng.

Session chỉ phục vụ hiển thị và khôi phục thông tin trong frontend; dữ liệu client
không được xem là bằng chứng xác thực hay dùng để cấp quyền API giáo viên.

## Màn hình chờ và ranh giới Task 3.3

`StudentWaiting` hiển thị tên thí sinh, lớp/nhóm, số phút và mã phòng, với trạng thái:

> Đang đợi giáo viên bắt đầu bài thi...

Biểu tượng đồng hồ cát có vòng pulse dùng Tailwind `animate-pulse`; tắt animation
khi người dùng bật `prefers-reduced-motion` qua `motion-reduce:animate-none`.

Thông tin phòng chờ lấy từ response join đã lưu. Task này không mở Socket.IO,
không polling API phòng của giáo viên, không thay đổi trạng thái phòng hoặc làm
bài/chấm điểm. Việc nhận sự kiện bắt đầu/đóng phòng sẽ được tích hợp ở Task 4.x.

## Responsive và ảnh nghiệm thu

Tailwind dùng một cột ở mobile, hai cột ở desktop cho trang join. Form có label,
thông báo lỗi, nút toàn chiều rộng và spinner. Màn hình chờ giới hạn chiều rộng,
tên dài được xuống dòng; các trạng thái chờ sử dụng `role="status"`.

Ảnh Chrome chụp giao diện thực với dữ liệu REST mock theo schema backend:

- [Join desktop 1440 px](screenshots/task_3_3/join-desktop.png).
- [Waiting desktop 1440 px](screenshots/task_3_3/waiting-desktop.png).
- [Join mobile 390 px](screenshots/task_3_3/join-mobile.png).
- [Waiting mobile 390 px](screenshots/task_3_3/waiting-mobile.png).

Đã kiểm tra không tràn ngang trên mobile, cả khi họ tên/tên lớp rất dài.

## Kiểm thử và chạy local

Từ thư mục gốc:

```powershell
Set-Location frontend
npm.cmd run build
npm.cmd test
npm.cmd run dev
```

Mở <http://localhost:5173/> hoặc <http://localhost:5173/join>, dùng mã phòng thật
do giáo viên tạo. Backend vẫn chạy theo cấu hình `VITE_API_BASE_URL` hiện có
(mặc định <http://localhost:8000>).

Kết quả: build production đạt; **58 browser tests đạt** (11 auth, 13 exam,
17 teacher room, 17 student entry). Các ca học sinh kiểm tra public routes,
không JWT, payload hai trường, loading/chặn lặp, validation, 404/409/422/503,
explicit retry, reload không tạo thêm học sinh, tiếp tục phiên, thiếu/hỏng/sai
session, mã khác, canonical URL, JWT giáo viên không ảnh hưởng join, storage
bị chặn, whitelist response, tab riêng, responsive/tên dài và reduced motion.

Kiểm thử trình duyệt dùng API mock; không ghi PostgreSQL hoặc gọi Gemini. Backend
và `models.py` không thay đổi trong Task 3.3. SHA256 model giữ nguyên:
`D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F`.
