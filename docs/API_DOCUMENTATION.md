# API Documentation — Quiz AI & Exam System

Base URL local `http://localhost:8000`. Swagger `/docs`, schema máy đọc `/openapi.json`; snapshot nghiệm thu ở [qa/openapi.json](qa/openapi.json). Socket.IO path `/socket.io`, không phải raw WebSocket. Backend entry point `main:app`.

## Quy ước dữ liệu và xác thực

REST dùng JSON `Content-Type: application/json`. Auth giữ hợp đồng snake_case hiện hữu (`mat_khau`, `access_token`); API đề/phòng/bài nộp và Socket xuất camelCase. Các schema này cũng chấp nhận snake_case cho input qua `populate_by_name`; nên dùng camelCase thống nhất ở client. ID là chuỗi, thời lượng là số nguyên phút, ngày tạo là ISO `YYYY-MM-DD` hoặc null. Timestamp Socket là epoch millisecond; client không được dùng thời gian máy mình làm deadline.

API giáo viên yêu cầu `Authorization: Bearer <JWT>`; Socket giáo viên kết nối với `auth: {token: '<JWT>'}`. JWT có hạn theo `ACCESS_TOKEN_EXPIRE_MINUTES`, mặc định 30 phút. Không có refresh-token API. Quyền sở hữu đề/phòng được kiểm tra ở service.

Học sinh dùng endpoint public với định danh nhận từ `/api/rooms/join`, không yêu cầu JWT. Không gửi token giáo viên kèm request học sinh. Cặp `idHs`/`idPhongThi` phải khớp bản ghi tham gia; đây là định danh ẩn danh hiện tại, chưa phải admission token bí mật riêng.

## Danh mục REST

| Method | Path | Quyền | Thành công / dữ liệu |
| --- | --- | --- | --- |
| GET | `/api/health` | Public | 200 `{"status":"ok","app":"Quiz AI & Exam System"}` |
| POST | `/api/auth/register` | Public | 201 thông tin giáo viên, ẩn mật khẩu |
| POST | `/api/auth/login` | Public | 200 `access_token`, `token_type: "bearer"` |
| GET | `/api/auth/me` | Giáo viên | 200 thông tin giáo viên hiện tại |
| POST | `/api/exams/preview-ai` | Giáo viên | 200 bản nháp và câu hỏi AI; không ghi DB |
| POST | `/api/exams/generate-ai` | Giáo viên | 201 sinh và lưu đề+câu hỏi một transaction |
| POST | `/api/exams` | Giáo viên | 201 lưu đề thủ công/đã kiểm duyệt |
| GET | `/api/exams` | Giáo viên | 200 array tóm tắt kho đề của người gọi |
| GET | `/api/exams/{id_de_thi}` | Chủ đề | 200 đề và câu hỏi gồm đáp án đúng, chỉ cho giáo viên |
| PUT | `/api/exams/{id_de_thi}` | Chủ đề | 200 thay thế metadata/danh sách câu hỏi |
| DELETE | `/api/exams/{id_de_thi}` | Chủ đề | 200 `{"idDeThi":"...","status":"deleted"}`; cascade câu hỏi |
| POST | `/api/rooms` | Giáo viên | 201 phòng `DANG_CHO`, mã random UNIQUE 6 ký tự |
| POST | `/api/rooms/join` | Public | 201 định danh học sinh và thông tin phòng |
| GET | `/api/rooms/{id_phong_thi}` | Chủ phòng | 200 metadata, `hocSinhList`, `ketQuaList` |
| POST | `/api/submissions/submit` | Public | 200 điểm bài làm đã chấm |
| GET | `/api/submissions/result?idHs=...&idPhongThi=...` | Public | 200 kết quả đã chấm, kể cả phòng đã đóng |

Đề đã được phòng thi tham chiếu không được sửa/xóa (409). PUT thay toàn bộ danh sách câu hỏi, không phải patch một câu hỏi. POST join tạo đồng thời `HocSinh` và `KetQuaThi` (`DANG_THI`, 0.0); điểm 0.0 ban đầu không phải điểm đã hoàn thành. `ketQuaList.diemSo` là null khi bài chưa hoàn thành.

### Đăng ký và đăng nhập

```json
{
  "email": "teacher@example.com",
  "mat_khau": "ExamplePass123!",
  "ho_ten": "Nguyễn Văn A",
  "truong": "THCS A",
  "tinh": "TP Hồ Chí Minh"
}
```

Đăng nhập gửi `email`, `mat_khau`; không dùng form OAuth2. Mật khẩu ít nhất 8 ký tự và tối đa 72 byte UTF-8, không có null byte. `truong`/`tinh` có thể bỏ hoặc null; nếu có chuỗi thì không được rỗng. Email trùng trả 409; đăng nhập sai trả 401. Giá trị mật khẩu được bcrypt, không xuất trong response.

```json
{"access_token":"<JWT>","token_type":"bearer"}
```

### Sinh đề AI và lưu sau kiểm duyệt

Gửi payload dưới đây đến `/api/exams/preview-ai` hoặc `/generate-ai`:

```json
{"tenDeThi":"Ôn tập nước","khoi":"6","rawText":"Nước có công thức H2O. Nước sôi ở 100 độ C ở áp suất tiêu chuẩn.","numQuestions":2}
```

Backend yêu cầu số câu nguyên ≥1 và văn bản không rỗng. UI giới hạn 1–50 câu. Upload `.txt` được frontend đọc UTF-8 (tối đa 1 MB), gửi chuỗi JSON `rawText`, không gửi multipart/PDF. Gemini nhận JSON Array với đúng sáu key snake_case; service validate toàn bộ array trước khi trả DTO camelCase. Timeout cứng 15 giây, một lần gọi SDK, không tự retry làm kéo dài deadline.

Preview trả dạng payload sau, có thể sửa rồi POST `/api/exams`:

```json
{
  "tenDeThi":"Ôn tập nước",
  "khoi":"6",
  "kieuTaiLieu":"TEXT",
  "noiDungTaiLieu":"Nước có công thức H2O.",
  "cauHoiList":[
    {"noiDung":"Công thức của nước là gì?","dapAnA":"H2O","dapAnB":"CO2","dapAnC":"O2","dapAnD":"NaCl","dapAnDung":"A"}
  ]
}
```

Đây là ví dụ minh họa một câu; số câu AI thực tế phải khớp `numQuestions`. Khi lưu, `soLuongCauHoi` được service tính từ array; không gửi ID câu hỏi/đề trong payload tạo hoặc PUT. Mỗi câu phải đủ nội dung+bốn phương án khác nhau và đáp án đúng A/B/C/D. Detail có thêm `idDeThi`, `idGv`, `soLuongCauHoi`, `ngayTao`, ID và foreign key từng câu. Chỉ giáo viên được đọc DTO có `dapAnDung`.

### Tạo phòng và học sinh tham gia

POST `/api/rooms`:

```json
{"idDeThi":"<exam-id>","tenLop":"Lớp 6A","thoiGianLamBai":30}
```

Response:

```json
{"idPhongThi":"<room-id>","maCodePhongThi":"AB12CD","tenLop":"Lớp 6A","thoiGianLamBai":30,"trangThaiPhong":"DANG_CHO","ngayTaoPhong":"2026-10-13"}
```

POST `/api/rooms/join`:

```json
{"hoVaTen":"Nguyễn Văn An","maCodePhongThi":"AB12CD"}
```

Response:

```json
{"idHs":"<student-id>","hoVaTen":"Nguyễn Văn An","maCodePhongThi":"AB12CD","idPhongThi":"<room-id>","tenLop":"Lớp 6A","thoiGianLamBai":30}
```

Không tự tạo học sinh khi Socket reconnect; dùng lại `idHs`. Mã phải đúng `[A-Z0-9]{6}`. Phòng không tồn tại trả 404; phòng `DA_DONG` trả 409. Học sinh vẫn được tham gia phòng `DANG_THI`, nhận deadline hiện tại.

### Nộp bài và xem lại điểm

POST `/api/submissions/submit`:

```json
{
  "idHs":"<student-id>",
  "idPhongThi":"<room-id>",
  "danhSachCauTraLoi":[
    {"idCauHoi":"<question-1>","dapAnChon":"A"},
    {"idCauHoi":"<question-2>","dapAnChon":null}
  ]
}
```

`dapAnChon` là A/B/C/D hoặc null (trường bắt buộc). Có thể bỏ câu chưa trả lời khỏi danh sách; câu thiếu/null tính sai. Câu hỏi trùng ID hoặc thuộc đề khác trả 422. Tổng câu dùng số câu thực trong DB, không tin số client gửi. Công thức `round((so_cau_dung / tong_so_cau) * 10, 2)`.

```json
{"idHs":"<student-id>","diemSo":6.67,"soCauDung":2,"tongSoCau":3,"trangThai":"HOAN_THANH"}
```

REST và Socket dùng cùng service. Bài nộp đầu tiên đã được nhận sẽ được giữ nguyên khi retry; không ghi đè bằng đáp án mới. Broadcast sau commit. GET result là read-only; chưa chấm trả 409, sai ID/phòng 404, thiếu runtime cần cho số câu đúng trả 503 và giữ nguyên điểm DB.

## Socket.IO

Frontend dùng singleton `src/services/socket.js`. Transport WebSocket, tự reconnect. Teacher xác thực JWT trong handshake và được kiểm tra lại ở thao tác start/close. Student không gửi JWT. Client phải join thành công trước khi sync/start/submit. Teacher và student cùng join room code; sự kiện roster/trạng thái/điểm được gửi riêng tới `teachers:<code>`.

### Client → Server (ACK)

| Event | Payload | ACK thành công / hành vi |
| --- | --- | --- |
| `join_exam_room` | `{maCodePhongThi,userType:"teacher"}` hoặc thêm `idHs` với `userType:"student"` | `{status:"ok",maCodePhongThi,userType,idHs,idPhongThi}` |
| `start_exam` | `{maCodePhongThi}` | `{status:"ok",exam:ExamStarted}`; owner teacher, DANG_CHO → DANG_THI |
| `sync_exam` | `{maCodePhongThi}` | `{status:"ok",serverTime,requestReceivedAt,exam,submission,studentState}` |
| `reconnect_exam` | `{maCodePhongThi,idHs}` | ACK sync + `remainingSeconds`, `examExpired`; nếu còn giờ khôi phục DANG_THI; không đổi deadline |
| `submit_exam` | `{maCodePhongThi,runId,answers:{"<question-id>":"A"},reason:"manual"}` | `{status:"ok",submission:Receipt,result:Grade}`; `reason` có thể `timeout` |
| `close_exam_room` | `{maCodePhongThi}` | `{status:"ok",idPhongThi,maCodePhongThi,trangThaiPhong:"DA_DONG"}` |

`exam`/`submission`/`studentState` trong sync có thể null. Reconnect quá deadline trả remaining 0 trong dữ liệu thời gian và không phục hồi DANG_THI. Submission receipt chứa `submissionId`, `runId`, `idHs`, `receivedAt`, `receivedLate`, `reason`, `duplicate`. ACK lỗi có dạng `{"status":"error","statusCode":422,"detail":"..."}`; HTTP status không áp dụng trực tiếp cho WebSocket ACK.

### Server → Client

| Event | Người nhận | Payload |
| --- | --- | --- |
| `room_students_updated` | Giáo viên trong phòng | `{maCodePhongThi,hocSinhList:[{idHs,hoVaTen,maCodePhongThi}]}` |
| `student_states_updated` | Giáo viên đồng bộ | `{maCodePhongThi,hocSinhList:[StudentState]}` |
| `exam_started` | Room code | `ExamStarted`: đề public và deadline |
| `student_disconnected` | Giáo viên trong phòng | `StudentState`, `trangThai:"MAT_KET_NOI"` |
| `student_reconnected` | Giáo viên trong phòng | `StudentState`, `trangThai:"DANG_THI"` |
| `student_connection_error` | Giáo viên trong phòng | Lỗi lưu trạng thái disconnect, không giả thành công |
| `student_submitted` | Giáo viên trong phòng | Grade + `idPhongThi`, `maCodePhongThi`, `hoVaTen` |
| `room_closed` | Room code | `{idPhongThi,maCodePhongThi,trangThaiPhong:"DA_DONG"}` |

`StudentState` có `idHs`, `hoVaTen`, `maCodePhongThi`, `idPhongThi`, `trangThai`, `stateVersion`, `stateChangedAt`, `disconnectedAt`, `frozenRemainingSeconds`; hai field cuối có thể null. Sự kiện disconnect chỉ đổi trạng thái sau khi socket cuối của học sinh mất kết nối, không đổi bài `HOAN_THANH`.

Payload câu hỏi public dùng whitelist Pydantic, không query/serialize `dap_an_dung`:

```json
{
  "runId":"<run-id>","maCodePhongThi":"AB12CD","idPhongThi":"<room-id>",
  "idDeThi":"<exam-id>","tenDeThi":"Ôn tập nước","tenLop":"6A",
  "thoiGianLamBai":1,"trangThaiPhong":"DANG_THI",
  "startsAt":1791870000000,"endsAt":1791870060000,"serverTime":1791870000000,
  "cauHoiList":[{"idCauHoi":"<question-id>","noiDung":"Công thức của nước?","dapAnA":"H2O","dapAnB":"CO2","dapAnC":"O2","dapAnD":"NaCl"}]
}
```

Ví dụ timestamp để minh họa thời lượng 1 phút. Payload thật của E2E: [qa/exam_started.json](qa/exam_started.json). Client đồng bộ dựa trên `serverTime`, `requestReceivedAt` và clock monotonic, tính lại thời gian từ `endsAt`; không decrement state mỗi giây để làm nguồn sự thật.

## Lỗi, transaction và giới hạn

| Status | Ý nghĩa |
| --- | --- |
| 400 | ERR_01: JSON/câu hỏi AI lỗi; rollback, không lưu đề rác |
| 401 | JWT thiếu/hết hạn/không hợp lệ hoặc đăng nhập sai |
| 403 | Socket teacher không sở hữu phòng / guest ID không khớp admission |
| 404 | Đề/phòng/kết quả không tồn tại trong phạm vi người gọi |
| 409 | Trùng email, phòng đóng/chưa bắt đầu, đề đang được sử dụng, kết quả chưa chấm |
| 422 | ERR_03: thiếu field, sai kiểu/enum, câu hỏi trùng hoặc không thuộc đề |
| 503 | ERR_01 timeout/API/mạng; database/runtime không khả dụng |

Lỗi REST thông thường `{"detail":"..."}`; lỗi validation FastAPI `{"detail":[{"loc":["body","..."],"msg":"...","type":"..."}]}`. Không gửi khóa bí mật, JWT hoặc mật khẩu vào log/báo cáo.

Transaction đề+câu hỏi: `services/exam_service.py` (transaction wrapper, flush → response validation → một commit, rollback khi lỗi). Student+result: `services/room_service.py::join_room`. Chấm điểm: `services/grading_service.py::grade_submission`, row lock và rollback. Đóng phòng: `services/room_lifecycle_service.py`. Router chỉ DI/request/response.

Socket worker truy vấn theo code/student/exam có WHERE và chạy ngoài event loop. Chạy một worker, giữ `backend/.runtime/`, chưa có Redis/multi-worker. Bài nộp muộn do offline được chấp nhận với dấu `receivedLate`; `DA_DONG` chặn bài mới và giữ các điểm đã chấm. API không xuất đáp án đúng cho học sinh ở cả exam/recovery/result. Các giới hạn demo và kết quả kiểm chứng đầy đủ: [QA_REPORT.md](QA_REPORT.md).
