# Task 3.2 — Teacher Room UI

## Routes và component

Hai route nằm trong `ProtectedRoute` và `TeacherLayout`:

| Route | Component | Chức năng |
| --- | --- | --- |
| `/rooms/create` | `RoomCreatePage` | Chọn đề thi, tên lớp/nhóm, thời gian; tạo phòng |
| `/rooms/create?examId=<id>` | `RoomCreatePage` | Chọn sẵn đề từ card hoặc trang chi tiết |
| `/rooms/:id/waiting` | `TeacherWaitingRoom` | Mã phòng, danh sách thí sinh, thông tin buổi thi |

Mã đầy đủ của component nghiệm thu:
[TeacherWaitingRoom.jsx](../frontend/src/pages/TeacherWaitingRoom.jsx).

Các component bổ trợ trong `frontend/src/components/rooms/`:

- `RoomCodePanel`: mã phòng lớn, nút sao chép và thông báo kết quả.
- `StudentRoster`: số lượng, danh sách theo ID học sinh, trạng thái trống và dữ liệu cũ.
- `RoomLoading`: skeleton cho tải kho đề hoặc tải phòng.

`services/roomService.js` chỉ gọi hai API đã có của Task 3.1. JWT tự động được
gắn bằng Axios interceptor dùng chung. `hooks/useRoomPolling.js` quản lý tải,
polling, hủy request và thử lại. `utils/roomValidation.js` kiểm tra form và
chuyển lỗi thành thông báo tiếng Việt.

Menu giáo viên có “Tạo phòng thi”. Card kho đề và trang chi tiết có liên kết
tạo phòng với ID đề được chọn sẵn. Không thêm giao diện tham gia cho học sinh.

## Luồng tạo phòng

1. Mở “Tạo phòng thi”, hoặc chọn “Tạo phòng thi” trên một đề trong kho.
2. GET `/api/exams` tải các đề của giáo viên. Đề không có câu hỏi không được chọn;
   kho chưa có đề phù hợp hiển thị hướng dẫn tạo đề trước.
3. Nhập tên lớp/nhóm và số nguyên phút dương. Validation chặn thiếu đề, tên
   trống, số lẻ, số âm, 0 và giá trị vượt kiểu Integer PostgreSQL.
4. Bấm “Tạo phòng”: khóa form, hiển thị spinner, chặn gửi lặp và POST `/api/rooms`.
5. Chỉ chuyển đến `/rooms/<idPhongThi>/waiting` sau response thành công.
   Lỗi giữ nguyên form; không tự động retry POST.

Payload chỉ có đúng ba trường của schema backend:

```json
{
  "idDeThi": "<ID đề thi>",
  "tenLop": "Lớp 6A",
  "thoiGianLamBai": 30
}
```

`thoiGianLamBai` là JSON number/int; tên lớp được trim. Không gửi ID giáo viên,
mã phòng hoặc trạng thái do client tự đặt. Backend cấp mã và trạng thái.

## Phòng chờ và polling

Mã do backend cấp được hiển thị nguyên vẹn, ví dụ `AB12CD`, bằng chữ lớn,
font monospace và nền tương phản. Đúng hợp đồng Task 3.1, mã có sáu ký tự
chữ in hoa/số; không đổi mã thành sáu chữ số. Nút sao chép lấy toàn bộ mã.
Nếu clipboard bị chặn, giao diện hướng dẫn sao chép thủ công.

`StudentRoster` hiển thị số học sinh và `hoVaTen` từ `hocSinhList`. Biểu tượng
chỉ xác nhận đã tham gia, không suy diễn trạng thái online từ bản ghi DB.

Polling GET `/api/rooms/<id>` thực hiện lần đầu khi mở trang, rồi chờ **3 giây
sau khi request kết thúc** để gửi lượt tiếp theo. Nhờ đó request chậm không bị
chồng lên nhau. Hủy timer/request khi rời trang hoặc thay ID phòng. Tab ẩn
tạm dừng; hiển thị lại tab tải lại danh sách. StrictMode cleanup cũng hủy lượt cũ.

Lỗi mạng/503 giữ danh sách thành công gần nhất, hiển thị lỗi và chú thích dữ
liệu cũ, rồi tự thử lại. Nút “Thử lại” có thể tải ngay. HTTP 403/404 xóa dữ liệu
đã hiển thị và dừng polling đến khi người dùng thử lại; 401 dùng interceptor
chung để đăng xuất. Phòng `DA_DONG` ngừng polling và hiển thị trạng thái đóng.

Nút lớn “Bắt đầu làm bài” có mặt trong phòng chờ nhưng **disabled**. Component
chưa phát sự kiện Socket.IO, chưa đổi trạng thái phòng hoặc khởi động đồng hồ.
Đây là vị trí để tích hợp Task 4.x. Việc tải roster qua REST polling được thực
hiện theo yêu cầu cụ thể của Task 3.2.

## Proof of Completion

Mã component đầy đủ nằm ở file được liên kết phía trên. Những phần render chính:

```jsx
const { id } = useParams()
const { room, loading, refreshing, error, updatedAt, retry } = useRoomPolling(id)
```

```jsx
<RoomCodePanel code={room.maCodePhongThi} />
<StudentRoster
  students={room.hocSinhList}
  updatedAt={updatedAt}
  stale={Boolean(error)}
  closed={room.trangThaiPhong === 'DA_DONG'}
/>
```

Ảnh chụp giao diện thật bằng Chrome, dùng dữ liệu REST mock đúng schema backend:

- [Form khởi tạo](screenshots/task_3_2/create-room.png).
- [Phòng chờ chưa có thí sinh](screenshots/task_3_2/waiting-empty.png).
- [Mã AB12CD và ba thí sinh sau polling](screenshots/task_3_2/waiting-students.png).
- [Form trên điện thoại](screenshots/task_3_2/create-mobile.png).
- [Phòng chờ trên điện thoại, gồm trường hợp tên rất dài](screenshots/task_3_2/waiting-mobile.png).

Ảnh kiểm chứng mã phòng lớn, danh sách học sinh và nút bắt đầu đang khóa.
Viewport desktop 1440 px, mobile 390 px; đã kiểm tra không tràn ngang.

## Kiểm thử và chạy local

Từ thư mục gốc dự án:

```powershell
Set-Location frontend
npm.cmd run build
npm.cmd test
npm.cmd run dev
```

Mở <http://localhost:5173/rooms/create> sau khi đăng nhập giáo viên. Backend
chạy ở <http://localhost:8000> hoặc theo `VITE_API_BASE_URL` hiện có.

Build production đạt; **41 browser tests đạt** (11 auth, 13 exam, 17 room).
Sau điều chỉnh thông báo phòng đóng, build và 17 room tests được chạy lại.
Các ca room bao gồm payload/Bearer, lựa chọn đề từ kho, validation, gửi một
lần, lỗi tạo giữ form, polling nhận thí sinh mới, phục hồi 503, 404 dừng polling,
quyền truy cập bị thu hồi, dừng khi rời trang/đóng phòng, request không chồng,
tab ẩn, clipboard, 401, protected routes và responsive/tên dài.

Kiểm thử trình duyệt mock REST API, không gọi Gemini và không ghi PostgreSQL.
Các API Task 3.1 đã được nghiệm thu PostgreSQL riêng tại `docs/room_apis.md`.
Task 3.2 không sửa backend hoặc model. SHA256 `models.py` giữ nguyên:
`D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F`.
