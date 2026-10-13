# Task 4.3 — Disconnect Handling & State Recovery

Tài liệu này ghi nhận nghiệm thu Task 4.3. Từ [Task 5.1](submission.md), bài nộp
được chấm ngay và chuyển HOAN_THANH; các phần phục hồi vẫn giữ nguyên cơ chế bảo vệ bài đã hoàn thành.

Đã triển khai ERR_02 cho học sinh ẩn danh: socket cuối cùng bị ngắt → commit `KetQuaThi.trang_thai = MAT_KET_NOI` → thông báo riêng cho giáo viên. Học sinh kết nối lại/F5 tự gửi `reconnect_exam`; nếu phòng còn thời gian, server khôi phục `DANG_THI`, trả đề công khai và thời gian còn lại.

## File triển khai

| File | Trách nhiệm |
| --- | --- |
| `backend/app/core/socket_manager.py` | Bản đồ SID ↔ học sinh, kiểm tra socket cuối cùng, handlers `disconnect`/`reconnect_exam`, thông báo riêng giáo viên |
| `backend/app/services/exam_recovery_service.py` | Transaction thay đổi trạng thái, snapshot DB theo phòng, kiểm tra deadline và khôi phục |
| `backend/app/schemas/exam_recovery.py` | Pydantic v2 cho request/ACK reconnect |
| `backend/app/schemas/socket.py` | DTO trạng thái, snapshot và lỗi persist |
| `backend/app/core/exam_runtime.py` | Ghi atomically mốc mất kết nối, thời gian đóng băng và phiên bản trạng thái |
| `frontend/src/services/socket.js` | Tự reconnect theo danh tính session; nhận trạng thái thí sinh cho giáo viên |
| `frontend/src/hooks/useExamSocket.js` | Chỉ cho tiếp tục làm bài khi khôi phục được xác nhận |
| `frontend/src/hooks/useExamCountdown.js` | Đóng băng số hiển thị khi offline, đồng bộ lại khi online |
| `frontend/src/hooks/useStudentExamAnswers.js` | Giữ nháp, khóa lựa chọn/gửi bài trong lúc khôi phục |
| `frontend/src/hooks/useRoomPolling.js` | Giữ trạng thái Socket mới hơn khi REST/snapshot cũ tới sau |
| `frontend/src/components/rooms/StudentRoster.jsx` | Thẻ màu cam + nhãn MẤT KẾT NỐI |
| `frontend/src/pages/StudentTaking.jsx` | Hiển thị trạng thái đang khôi phục và khóa form |

## Disconnect Code Proof và Transaction Safety

`socket_manager.disconnect()` lấy membership bằng SID, bỏ SID khỏi bản đồ, kiểm tra còn socket khác của cùng học sinh hay không. Chỉ khi socket cuối cùng mất kết nối mới gọi service trong worker thread:

```python
state = await asyncio.to_thread(
    exam_recovery_service.mark_disconnected, previous, disconnected_at,
)
```

Trong `exam_recovery_service.mark_disconnected()`, `result` là đối tượng ORM **KetQuaThi** được truy vấn theo cả `id_hs`, `id_phong_thi` và mã phòng, khóa bằng `with_for_update()`:

```python
try:
    # ... scoped lookup, validate and prepare freeze metadata ...
    exam_flow_service.runtime_store.write_recovery(state)
    result.trang_thai = TrangThaiBaiLam.MAT_KET_NOI
    db.flush()
    db.commit()
    return state
except (SQLAlchemyError, OSError, ValueError) as exc:
    db.rollback()
    raise HTTPException(
        status_code=503,
        detail="Unable to persist disconnected student state.",
    ) from exc
except Exception:
    db.rollback()
    raise
```

Chỉ sau commit thành công, handler phát:

```python
await self.server.emit(
    "student_disconnected", state.model_dump(by_alias=True),
    room=self.teacher_room(previous.ma_code_phong_thi),
)
```

`teacher_room(code)` là `teachers:<code>`, chỉ giáo viên sở hữu đã xác thực JWT được vào. Học sinh và giáo viên phòng khác không nhận thông báo cá nhân này. Nếu DB/file lỗi, transaction rollback và phát `student_connection_error` cho giáo viên; không phát trạng thái MAT_KET_NOI chưa được commit.

## State Output Proof — Socket.IO thật

Lần kiểm chứng cuối chạy Uvicorn + Node `socket.io-client`, chủ động terminate WebSocket transport cuối cùng. Payload `student_disconnected` gửi giáo viên:

```json
{
  "idHs": "fixture-student",
  "hoVaTen": "Nguyễn Văn An",
  "maCodePhongThi": "AB12CD",
  "idPhongThi": "fixture-teacher-room",
  "trangThai": "MAT_KET_NOI",
  "stateVersion": 1,
  "stateChangedAt": 1791867967610,
  "disconnectedAt": 1791867967610,
  "frozenRemainingSeconds": 1800
}
```

Wire format dùng camelCase: `trangThai` tương ứng `KetQuaThi.trang_thai`.

Server khôi phục cùng học sinh và trả ACK với `studentState.trangThai = DANG_THI`, `remainingSeconds = 1800`, `examExpired = false`, giữ nguyên `runId` và `endsAt = 1791869767559`. Lần kiểm chứng này reconnect trong cùng giây; unit test bổ sung mô phỏng ngắt tại giây 10, khôi phục tại giây 25 và xác nhận thời gian giảm từ 1790 còn 1775 giây.

Payload đầy đủ: [student_disconnected_response.json](student_disconnected_response.json), [student_reconnect_response.json](student_reconnect_response.json). Log server và client: [exam_recovery_proof.log](exam_recovery_proof.log). Script còn đọc snapshot trạng thái từ DB qua socket giáo viên sau mỗi chuyển đổi, xác nhận MAT_KET_NOI rồi DANG_THI, điểm vẫn `0.0`.

## Khôi phục và đóng băng

Học sinh dùng danh tính có sẵn trong sessionStorage; không tạo thêm `HocSinh`/`KetQuaThi`, không yêu cầu tài khoản. Mỗi lần socket connect thành công, frontend join đúng phòng rồi gọi:

```javascript
export const reconnectExam = (membership) => examRequest('reconnect_exam', {
  maCodePhongThi: membership.maCodePhongThi,
  idHs: membership.idHs,
})
```

Server kiểm tra payload bằng Pydantic strict trước SQL, SID đã join, đúng vai trò student và ID khớp membership. Service kiểm tra lại học sinh thuộc phòng, room còn mở và deadline gốc. Reconnect hết giờ trả đề an toàn cùng `remainingSeconds: 0`/`examExpired: true`, giữ MAT_KET_NOI; không cấp thêm thời gian. Bài HOAN_THANH giữ nguyên trạng thái/điểm và không được mở lại form. Phòng chờ chưa có deadline vẫn khôi phục DANG_THI của `KetQuaThi`, trả `exam: null`.

Đóng băng gồm trạng thái DB, thời điểm server nhận event disconnect, snapshot thời gian còn lại và lựa chọn học sinh. Client khóa radio/nộp bài và đóng băng **đồng hồ hiển thị** trong lúc offline/khôi phục. Deadline chung của phòng tiếp tục chạy; khi trở lại, đồng hồ dùng thời gian còn lại thực tế. Không kéo dài buổi thi vì một học sinh mất mạng. Nếu kết nối lại sau hết giờ, nháp vẫn được giữ để gửi bài nộp tự động theo cơ chế nhận bài chưa chấm điểm đã được duyệt ở Task 4.2.

Metadata đóng băng lưu tại `backend/.runtime/recovery-<hash>.json`, không thêm bảng/cột. Cùng cơ chế atomic write của Task 4.2, file được chuẩn bị trước commit; snapshot luôn lấy trạng thái DB làm nguồn chính. Lock theo mã phòng phối hợp join/start/disconnect/reconnect/sync, tránh trạng thái cũ hoặc file đang chuẩn bị bị phát ra giữa transaction. `stateVersion` giúp frontend bỏ qua snapshot đến muộn; REST không xóa trạng thái Socket mới.

Giáo viên reload vẫn nhận `student_states_updated` từ truy vấn WHERE theo đúng phòng, kể cả học sinh hiện không còn socket. Đồng bộ mỗi 30 giây và khi reconnect giúp bổ sung snapshot. Truy vấn đều chạy trong worker thread có Session riêng, không SELECT toàn bảng trong event loop.

## Kiểm chứng

- Backend: bộ hồi quy **112 tests đạt**; sau bổ sung lock đồng bộ, **16 recovery tests + 13 exam flow tests** chạy lại đạt, bao gồm một ca race mới. Tổng 113 ca backend đã kiểm chứng.
- Frontend: toàn bộ **84 browser tests đạt**, gồm 7 ca mới cho trạng thái giáo viên, snapshot cũ, reconnect tự động, F5, hết giờ, lỗi khôi phục và bài đã hoàn thành.
- Production build đạt trên mã nguồn frontend cuối.
- Socket.IO thật `verify_socket_setup.py --recovery` đạt trên mã nguồn backend cuối: nhiều socket, ngắt transport cuối cùng, thông báo chỉ chủ phòng, teacher reload, DB states, deadline giữ nguyên, đề không có `dap_an_dung`/`dapAnDung`.
- [Giáo viên báo mất kết nối](screenshots/task_4_3/teacher-disconnected.png), [học sinh đang khôi phục](screenshots/task_4_3/student-recovering.png), [học sinh đã khôi phục](screenshots/task_4_3/student-recovered.png). Ảnh UI lấy từ Chrome với REST/Socket mock; JSON/log phía trên lấy từ Socket.IO thật.

Lệnh kiểm chứng từ thư mục gốc, từng nhóm chạy trong terminal riêng:

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
..\.venv\Scripts\python.exe -B verify_socket_setup.py --recovery
```

```powershell
Set-Location .\frontend
npm.cmd test
npm.cmd run build
```

Chạy local: `uvicorn main:app --reload --port 8000` từ backend bằng Python trong `.venv`, `npm.cmd run dev` từ frontend. Runtime hiện dùng một Uvicorn worker; giữ `backend/.runtime` để giữ deadline và metadata qua restart. Thiếu deadline không cản ghi MAT_KET_NOI, nhưng khôi phục phòng đang thi trả 503 thay vì tự tạo thời gian mới.

`models.py` giữ nguyên SHA256:

```text
D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F
```

Kiểm thử dùng DB/runtime tạm, không ghi PostgreSQL dự án. Chấm điểm đã có ở Task 5.1; leaderboard vẫn thuộc Task 5.2.
