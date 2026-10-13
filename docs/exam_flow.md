# Task 4.2 — Luồng thi trực tiếp và đồng hồ đồng bộ

Đã triển khai giáo viên bắt đầu thi, phát đề qua Socket.IO, màn hình làm bài của học sinh và đồng hồ chung. Task 4.2 ban đầu chỉ tiếp nhận bài và trả receipt. Chấm điểm và chuyển KetQuaThi sang HOAN_THANH hiện đã được nối ở [Task 5.1](submission.md). Xử lý MAT_KET_NOI và khôi phục đã có ở [Task 4.3](exam_recovery.md).

## Mã nguồn và giao diện

| File | Trách nhiệm |
| --- | --- |
| `backend/app/schemas/exam_flow.py` | Hợp đồng Pydantic v2 cho đề công khai, yêu cầu bắt đầu/đồng bộ/nộp và xác nhận bài |
| `backend/app/services/exam_flow_service.py` | Kiểm tra quyền, chuyển trạng thái trong transaction, lấy câu hỏi an toàn, đồng bộ phiên, tiếp nhận đáp án |
| `backend/app/core/exam_runtime.py` | Ghi atomically deadline và bài nộp vào file riêng của server |
| `backend/app/core/socket_manager.py` | Xử lý `start_exam`, `sync_exam`, `submit_exam`; gọi service trong worker thread và broadcast theo mã phòng |
| `frontend/src/services/socket.js` | Gọi event có ACK, nhận `exam_started`, đồng bộ khi join/reconnect/hiện lại tab và mỗi 30 giây |
| `frontend/src/hooks/useExamSocket.js` | Trạng thái kết nối, phiên thi, mốc đồng hồ và xác nhận bài nộp |
| `frontend/src/hooks/useExamCountdown.js` | Cập nhật đồng hồ theo deadline chung |
| `frontend/src/hooks/useStudentExamAnswers.js` | Chọn đáp án, lưu nháp, khóa snapshot nộp, tự nộp và thử lại |
| `frontend/src/utils/examRun.js` | Kiểm tra đề công khai, tính thời gian còn lại và bù độ trễ mạng |
| `frontend/src/utils/studentAnswerStorage.js` | Nháp localStorage theo học sinh + run ID |
| `frontend/src/components/ExamCountdown.jsx` | Đồng hồ phút:giây |
| `frontend/src/pages/TeacherWaitingRoom.jsx` | Nút bắt đầu, trạng thái ĐANG THI và đồng hồ giáo viên |
| `frontend/src/pages/StudentWaiting.jsx` | Nhận phiên thi rồi chuyển sang màn hình làm bài |
| `frontend/src/pages/StudentTaking.jsx` | Route `/exam-room/:code/taking`, lựa chọn A–D, nộp và xác nhận |

Luồng UI: giáo viên mở `/rooms/:id/waiting` → bấm **Bắt đầu làm bài** → server commit trạng thái → học sinh tự chuyển từ chờ sang `/exam-room/:code/taking` → chọn đáp án → bấm **Nộp bài** hoặc tự nộp tại `00:00` → chỉ hiển thị **Đã nhận bài nộp** sau ACK hợp lệ. Nếu chưa được xác nhận, snapshot giữ nguyên để thử lại.

## State Output Proof

Payload `exam_started` thực tế được ghi nguyên vẹn tại [exam_started_response.json](exam_started_response.json). Đây là trích đoạn của lần kiểm chứng bằng Uvicorn và Node Socket.IO client thật:

```json
{
  "maCodePhongThi": "AB12CD",
  "runId": "61cac6ad-2559-476f-8048-51345d742e6a",
  "thoiGianLamBai": 30,
  "trangThaiPhong": "DANG_THI",
  "startsAt": 1791841928967,
  "endsAt": 1791843728967,
  "cauHoiList": [{
    "idCauHoi": "d4649746-3792-458e-8a84-062d6d87567c",
    "noiDung": "Water formula?",
    "dapAnA": "H2O",
    "dapAnB": "CO2",
    "dapAnC": "O2",
    "dapAnD": "NaCl"
  }]
}
```

Quy ước wire format là camelCase: `trangThaiPhong` ánh xạ tới `PhongThi.trang_thai_phong`. Script kiểm chứng đọc lại DB, xác nhận enum `TrangThaiPhongThi.DANG_THI` và `KetQuaThi` vẫn `DANG_THI`, điểm `0.0`. Log: [exam_flow_proof.log](exam_flow_proof.log).

## Schema và bảo mật đề

`StudentQuestionSchema` chỉ khai báo ID, nội dung và bốn lựa chọn. Truy vấn trong `start_exam()` không lấy cột đáp án đúng:

```python
rows = db.execute(select(
    CauHoi.id_cau_hoi, CauHoi.noi_dung,
    CauHoi.dap_an_a, CauHoi.dap_an_b, CauHoi.dap_an_c, CauHoi.dap_an_d,
).where(CauHoi.id_de_thi == room.id_de_thi).order_by(CauHoi.id_cau_hoi)).all()
```

Kết quả được kiểm tra qua `StudentQuestionSchema`, sau đó `ExamStartedSchema.model_dump(by_alias=True)` trước khi phát. Frontend cũng từ chối câu hỏi chứa `dapAnDung` hoặc `dap_an_dung`. Đường đồng bộ sau reload dùng cùng schema công khai.

ID và nội dung giữ kiểu `str` của models; `thoi_gian_lam_bai` là `int`; lựa chọn nộp chỉ nhận `A|B|C|D`. Schema nền có `strict=True`, `extra='forbid'`; payload sai bị chặn trước truy vấn, ACK Socket mang `statusCode: 422`. Metadata `runId`, timestamps và receipt là hợp đồng phiên Socket, không phải cột DB mới.

Giáo viên phải join đúng phòng và JWT vẫn được kiểm tra quyền sở hữu khi bắt đầu/đồng bộ. Học sinh dùng ID đã tạo qua join ẩn danh; không thể phát lệnh bắt đầu. Câu hỏi được lọc theo đề của phòng. Toàn bộ SQL chạy trong `asyncio.to_thread`, mỗi worker mở Session riêng; không có SELECT toàn bảng trong event loop.

## Transaction Safety Proof

`exam_flow_service.start_exam()` nằm trong `try-except`, khóa hàng phòng bằng `with_for_update()`, ghi deadline rồi cập nhật:

```python
runtime_store.write_run(run)
room.trang_thai_phong = TrangThaiPhongThi.DANG_THI
db.flush()
db.commit()
```

Các nhánh `HTTPException`, `ValidationError`, lỗi SQL/file và ngoại lệ khác đều gọi `db.rollback()` trước khi raise. `socket_manager.start_exam()` chỉ phát `exam_started` khi service đã trả về sau commit thành công. Test ép commit lỗi chứng minh DB vẫn `DANG_CHO` và không có broadcast. File deadline được chuẩn bị trước commit chỉ được phục vụ khi DB đã `DANG_THI`; nếu commit lỗi, lần bắt đầu lại thay thế file đó.

SHA256 `backend/app/models/models.py` trước và sau đều là:

```text
D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F
```

## Đồng hồ, reload và nộp bài

Server tạo duy nhất `startsAt`, `endsAt` và `runId`. Bấm bắt đầu lại trả đúng phiên cũ, không đặt lại thời gian. Cả giáo viên và học sinh tính `ceil((endsAt - serverNow)/1000)`; giữa các lần đồng bộ, thời gian trôi dựa trên `performance.now()`. Độ trễ ước tính là một nửa RTT sau khi trừ thời gian xử lý của server (`serverTime - requestReceivedAt`), tránh trừ nhầm thời gian khi truy vấn chậm. Đây là đồng bộ có bù độ trễ ước tính; mạng bất đối xứng vẫn có thể tạo sai lệch nhỏ.

Deadline và receipt lưu tại `backend/.runtime/`, được `.gitignore` bỏ qua và không phục vụ qua HTTP. Ghi file dùng flush/fsync + `os.replace`; giữ file giúp khôi phục khi server restart. Nếu phòng đang thi nhưng file phiên bị mất/hỏng, trả lỗi 503 thay vì tạo deadline mới. Cách lưu hiện tại phục vụ local với **một Uvicorn worker**; chạy nhiều worker cần kho runtime dùng chung và khóa phân tán.

Đáp án chọn được lưu localStorage theo `idHs + runId`; danh tính học sinh vẫn ở sessionStorage. Tại 00:00 hoặc khi bấm nộp, client khóa snapshot, gửi `submit_exam` và chờ receipt. Lỗi mạng giữ pending; reconnect hoặc nút thử lại gửi cùng snapshot. Server lưu bài đầu tiên cho mỗi học sinh/phiên; retry trả cùng receipt với `duplicate=true`. Từ Task 5.1, bài được chấm ngay, cập nhật HOAN_THANH và ACK có thêm result. Bài trễ vẫn được chấm, receipt đánh dấu `receivedLate=true`; chi tiết tại [submission.md](submission.md).

## Kiểm chứng đã chạy

- Backend: toàn bộ 97 tests đạt; sau bổ sung timestamp xử lý server, 13 tests exam flow chạy lại đạt.
- Frontend: bộ hồi quy 76 tests đạt; sau kiểm tra độ trễ và thêm ca thứ 14 cho exam flow, 36 tests liên quan (14 flow, 17 teacher room, 5 socket) chạy lại đạt. Tổng 77 ca frontend đã được kiểm chứng.
- `npm.cmd run build` thành công trên mã nguồn cuối.
- `verify_socket_setup.py --flow` đạt trên mã nguồn cuối: đúng chủ phòng, DANG_THI, payload không đáp án đúng, cách ly phòng khác, deadline giống nhau, retry start không reset, reconnect giữ deadline, nhận bài idempotent và không chấm điểm. Script dùng DB SQLite và thư mục runtime tạm, không ghi PostgreSQL dự án.
- [Giáo viên đang thi](screenshots/task_4_2/teacher-live.png), [học sinh làm bài](screenshots/task_4_2/student-taking.png), [đã nhận bài](screenshots/task_4_2/submission-received.png), [mobile 390px](screenshots/task_4_2/taking-mobile.png). Ảnh UI lấy từ trình duyệt với REST/Socket mock; log và JSON nêu trên lấy từ kết nối Socket thật.

Từ thư mục gốc, mỗi nhóm lệnh chạy trong terminal riêng:

```powershell
# Backend (PostgreSQL và JWT đã cấu hình từ các task trước)
Set-Location .\backend
..\.venv\Scripts\python.exe -m uvicorn main:app --reload --port 8000
```

```powershell
# Frontend
Set-Location .\frontend
npm.cmd run dev
```

Lệnh kiểm chứng, cũng từ thư mục gốc:

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
..\.venv\Scripts\python.exe -B verify_socket_setup.py --flow
```

```powershell
Set-Location .\frontend
npm.cmd test
npm.cmd run build
```

Tham chiếu: [MDN performance.now](https://developer.mozilla.org/en-US/docs/Web/API/Performance/now) về đồng hồ monotonic; [python-socketio AsyncServer API](https://python-socketio.readthedocs.io/en/stable/api_server.html) về đồng bộ emit khi có nhiều tác vụ đồng thời.
