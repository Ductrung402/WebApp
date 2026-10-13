# Task 4.1 — Socket.IO Setup

Tài liệu và log này ghi nhận nghiệm thu Task 4.1. Luồng bắt đầu/làm bài đã được
bổ sung ở [Task 4.2](exam_flow.md); cập nhật trạng thái mất kết nối và khôi phục
đã có ở [Task 4.3](exam_recovery.md).

## Hạ tầng và entry point

Backend dùng `python-socketio` 5.x, frontend dùng `socket.io-client` 4.x đã có
trong dependencies. `backend/main.py` xuất hai object:

- `fastapi_app`: FastAPI chứa REST API, docs, middleware và lifespan.
- `app`: ASGI wrapper tích hợp Socket.IO, dùng cho lệnh Uvicorn hiện có.

Khởi tạo server trong [socket_manager.py](../backend/app/core/socket_manager.py):

```python
sio = socketio.AsyncServer(async_mode="asgi", cors_allowed_origins="*")
```

Wrapper trong [main.py](../backend/main.py):

```python
app = socketio.ASGIApp(sio, other_asgi_app=fastapi_app)
```

`/socket.io` được Engine.IO xử lý; các request REST, `/docs`, `/openapi.json`
và lifespan được chuyển đến FastAPI. Lifespan vẫn kiểm tra cấu hình JWT và
shutdown Socket.IO khi dừng server. Các test/script cần dependency overrides
import `fastapi_app`; lệnh chạy server tiếp tục là `main:app`.

Pattern ASGI tham chiếu [tài liệu server python-socketio](https://python-socketio.readthedocs.io/en/stable/server.html).

## Event và hợp đồng dữ liệu

`connect` nhận auth tùy chọn `{ "token": "<JWT giáo viên>" }`. Học sinh dùng
auth rỗng, không cần tài khoản. Handler ghi log SID, không ghi token/payload.

`join_exam_room` dùng `SocketJoinSchema` Pydantic v2. Frontend gửi camelCase,
đồng thời server hỗ trợ tên snake_case trong prompt qua `populate_by_name=True`.

Học sinh dùng ID từ POST `/api/rooms/join` của Task 3.1:

```json
{"maCodePhongThi": "AB12CD", "userType": "student", "idHs": "<ID học sinh>"}
```

Giáo viên gửi JWT qua auth và packet:

```json
{"maCodePhongThi": "AB12CD", "userType": "teacher", "idHs": null}
```

Mã phải đúng `[A-Z0-9]{6}`, role chỉ nhận `teacher` hoặc `student`, ID học sinh
phải là string không trống khi role là student. Teacher không được chỉ định ID
học sinh. Payload sai format trả ACK lỗi trước khi gọi database worker:

```json
{"status": "error", "statusCode": 422, "detail": "Invalid room join payload."}
```

Đây là ACK Socket.IO, không phải HTTP response. Các lỗi 401/403/404/409/503 cũng
dùng envelope này; không trả chi tiết database nội bộ. ACK thành công:

```json
{
  "maCodePhongThi": "AB12CD",
  "userType": "student",
  "idHs": "fixture-student",
  "status": "ok",
  "idPhongThi": "fixture-teacher-room"
}
```

Schema đầy đủ: [schemas/socket.py](../backend/app/schemas/socket.py).

## Room ID và phân quyền

Sau xác minh, mọi socket được đưa vào room đúng mã phòng:

```python
await self.server.enter_room(sid, membership.ma_code_phong_thi)
```

Socket teacher còn được đưa vào `teachers:<code>`. Chỉ nhóm riêng này nhận
event `room_students_updated`; học sinh không nhận roster chứa thông tin bạn học.
Teacher phải có JWT hợp lệ và sở hữu `PhongThi`. Student phải tồn tại theo đúng
cặp `id_hs` và `ma_code_phong_thi`; join Socket.IO không tạo thêm bản ghi học sinh.
Room không tồn tại/đóng sẽ bị từ chối.

Roster ví dụ gửi đến `teachers:AB12CD`:

```json
{
  "maCodePhongThi": "AB12CD",
  "hocSinhList": [
    {"idHs": "fixture-student", "hoVaTen": "Nguyễn Văn An", "maCodePhongThi": "AB12CD"}
  ]
}
```

Danh sách này là membership Socket.IO đang kết nối, được lưu trong memory theo
mã phòng và ID học sinh. Nhiều socket/tab của cùng một `idHs` chỉ xuất hiện một
lần. Teacher vào sau vẫn nhận snapshot hiện có. Disconnect giải phóng SID và
broadcast snapshot mới; học sinh còn socket khác không bị xóa khỏi membership.
Kiểm tra socket còn kết nối sau bước xác minh ngăn join bị trễ tạo membership ma.

## Tuân thủ I.4: không SQL quét rộng trong event loop

`join_exam_room` validate packet, rồi dùng:

```python
membership = await asyncio.to_thread(
    socket_service.authorize_socket_join, payload, auth.get("token"),
)
```

[socket_service.py](../backend/app/services/socket_service.py) mở/đóng Session
trong worker thread và chỉ trả DTO, không đưa ORM Session/object sang event loop.
Truy vấn phòng lấy ba cột, lọc mã UNIQUE, `LIMIT 1`; truy vấn học sinh lấy ba cột,
lọc primary key và mã phòng, `LIMIT 1`. Teacher JWT dùng lookup giáo viên theo
primary key. Không có `SELECT ALL` hoặc lazy-load roster toàn DB trong handler.

Broadcast lấy roster từ map của chính room trong memory, không truy vấn DB.
Tại Task 4.1, `connect` và `disconnect` không gọi SQL; phần setup không ghi DB.
Từ Task 4.3, `disconnect` gọi service trong worker thread để cập nhật `KetQuaThi`;
model vẫn giữ nguyên và event loop không chạy SQL trực tiếp.

Các test thu SQL thực thi và xác nhận tất cả query student join có `WHERE` và
`LIMIT`, đồng thời thread thực thi khác thread event loop.

## Singleton và hook frontend

Code đầy đủ: [services/socket.js](../frontend/src/services/socket.js).

```js
export const socket = io(socketUrl, {
  autoConnect: false,
  transports: ['websocket'],
  reconnection: true,
  timeout: 10000,
})
```

URL ưu tiên `VITE_SOCKET_URL`, tiếp đến `VITE_API_BASE_URL`, mặc định
`http://localhost:8000`. Chỉ đặt origin công khai trong `VITE_*`, không đặt secret.
Các tùy chọn dựa trên [tài liệu client Socket.IO](https://socket.io/docs/v4/client-options).

- `joinExamRoom`: gửi join và chờ ACK tối đa 5 giây.
- `subscribeRoomStudents`: lắng nghe roster và trả hàm unsubscribe.
- `connectExamRoom`: nối singleton, join sau `connect`, tự join lại mỗi reconnect,
  lọc roster theo code/role và cleanup listener/transport khi không còn dùng.
- `useExamSocket`: hook React trả trạng thái, lỗi và hàm thử kết nối lại.

TeacherWaitingRoom gắn hook bằng mã phòng và JWT, hợp nhất học sinh nhận từ
Socket.IO vào danh sách đã tham gia. Polling REST của Task 3.2 vẫn hỗ trợ fallback;
response polling cũ không xóa học sinh đã được Socket.IO xác nhận. Roster kết nối
rỗng sau disconnect không xóa bản ghi thí sinh đã tham gia khỏi UI/DB.

StudentWaiting gắn hook bằng mã và `idHs` trong session hiện có; không gửi JWT
giáo viên dù cùng trình duyệt đang đăng nhập. Cleanup xử lý đổi route/role/room và
StrictMode; listener không được đăng ký lặp mỗi lần reconnect. Trang join công khai
và các trang khác không tự mở kết nối Socket.IO.

Ở thời điểm nghiệm thu Task 4.1, nút bắt đầu vẫn disabled. Task 4.2/4.3 hiện đã
bổ sung phát đề, timer, cập nhật MAT_KET_NOI và khôi phục. Membership dùng một Uvicorn worker trong
cấu hình local; nếu mở rộng nhiều worker cần thêm manager chia sẻ phù hợp.

## Log kết nối thật — Proof of Completion

Log đầy đủ tại [socket_setup_proof.log](socket_setup_proof.log). Trích log server:

```text
socket_connected sid=rVpwdevvZCz6cDmhAAAF
socket_joined sid=rVpwdevvZCz6cDmhAAAF room=AB12CD role=student
```

Log client JavaScript thực tế:

```text
CLIENT connected role=student sid=rVpwdevvZCz6cDmhAAAF transport=websocket
CLIENT joined room=AB12CD role=student status=ok
CLIENT teacher roster room=AB12CD students=1
PASS: real WebSocket connect/join, teacher-only roster, room isolation, ownership=403 and reconnect/rejoin.
```

`backend/verify_socket_setup.py` chạy Uvicorn thật ở cổng localhost tạm thời,
gọi `frontend/scripts/verify-socket.mjs` sử dụng package `socket.io-client` thật.
JWT chỉ được chuyển qua stdin, không nằm trong argv/file/log. Script dùng SQLite
fixture tạm có đúng models hiện có, kiểm tra hai phòng, teacher/student, quyền sở
hữu, roster riêng, disconnect/rejoin và REST health qua ASGI wrapper. Database
tạm bị xóa sau nghiệm thu; không ghi PostgreSQL của dự án.

## Chạy local và kiểm thử

Backend, từ thư mục gốc:

```powershell
Set-Location backend
..\.venv\Scripts\python.exe -m uvicorn main:app --reload --port 8000
```

Terminal frontend riêng:

```powershell
Set-Location frontend
npm.cmd run dev
```

Mở phòng chờ giáo viên, rồi dùng trình duyệt/tab học sinh để POST join và mở
phòng chờ. Trạng thái hiển thị “Đã kết nối phòng chờ” sau ACK thành công.

Kiểm thử backend, từ `backend`:

```powershell
..\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
..\.venv\Scripts\python.exe -B verify_socket_setup.py
```

Kiểm thử frontend, từ `frontend`:

```powershell
npm.cmd run build
npm.cmd test
```

Kết quả: **84 backend tests đạt**, gồm 16 Socket tests; **63 browser tests đạt**,
gồm 5 Socket tests; build production đạt. Sau sửa race với polling cũ, build và
22 test Socket/teacher room được chạy lại. Browser tests mock protocol WebSocket
bằng [Playwright WebSocketRoute](https://playwright.dev/docs/api/class-websocketroute),
còn script live ở trên kiểm chứng transport thực giữa hai SDK.

SHA256 `backend/app/models/models.py` trước/sau giữ nguyên:
`D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F`.
