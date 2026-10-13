# Task 5.2 — Live Leaderboard & Student Result View

Đã triển khai trang kết quả học sinh, bảng điểm trực tiếp của giáo viên, thống kê, CSV và đóng phòng thật. Điểm lấy từ backend Task 5.1; không tính lại điểm bài thi tại frontend.

## Component, route và luồng UI

| File | Trách nhiệm |
| --- | --- |
| `frontend/src/pages/StudentResult.jsx` | `/exam-room/:code/result`: điểm lớn, số câu đúng, lời động viên, loading/error/retry và về trang chủ |
| `frontend/src/components/rooms/TeacherLiveLeaderboard.jsx` | Bảng điểm trong `/rooms/:id/waiting` khi đang thi/đã đóng, xếp hạng, thống kê và CSV |
| `frontend/src/components/rooms/CloseRoomDialog.jsx` | Xác nhận đóng phòng, cảnh báo thí sinh chưa hoàn thành, khóa thao tác khi chờ server |
| `frontend/src/services/resultService.js` | Đọc lại kết quả đúng học sinh/phòng qua API công khai, không gửi JWT giáo viên |
| `frontend/src/utils/results.js` | Kiểm tra DTO, định dạng điểm, xếp hạng, thống kê và CSV |
| `frontend/src/services/socket.js` | Nhận `student_submitted`, gọi `close_exam_room`, nhận `room_closed` và cleanup listener |
| `frontend/src/hooks/useRoomPolling.js` | Kết hợp roster, điểm REST và event Socket theo ID; giữ kết quả hoàn thành khi snapshot cũ tới sau |

Học sinh: bấm nộp hoặc tự nộp → ACK có receipt + result → chuyển ngay sang trang kết quả. Result từ ACK được hiển thị tức thì, đồng thời API xác nhận lại. Reload/direct link đọc từ server; trang kết quả không mở Socket, không nộp lại bài và vẫn dùng được sau khi phòng đóng.

Giáo viên: mở phòng đang thi → nhận `student_submitted` → cập nhật đúng dòng, đổi nhãn **ĐÃ HOÀN THÀNH** màu xanh lá → xếp lại điểm giảm dần và tính lại thống kê. Số người đã nộp đếm trạng thái HOAN_THANH; điểm trung bình tính trên bài đã hoàn thành. Chưa có điểm hiển thị `—`, không dùng điểm mặc định 0.0 của bài đang làm. Điểm 0 đã chấm là điểm hợp lệ và được tính bình thường. Đồng điểm dùng cùng hạng, sau đó thứ tự tên/ID ổn định.

CSV xuất toàn bộ thí sinh theo thứ tự bảng, các cột Hạng/Họ tên/Trạng thái/Điểm. File UTF-8 BOM hỗ trợ tiếng Việt trong Excel, escape dấu nháy và bảo vệ tên bắt đầu bằng ký tự công thức. Bài chưa có điểm để trống cột điểm.

## Code cập nhật realtime — Proof of Completion

Trong [services/socket.js](../frontend/src/services/socket.js), chỉ nhận thông báo đúng phòng và đúng vai trò giáo viên, kiểm tra DTO trước khi cập nhật:

```javascript
const onSubmitted = (packet) => {
  if (disposed || membership.userType !== 'teacher' || packet?.maCodePhongThi !== membership.maCodePhongThi || typeof packet.hoVaTen !== 'string') return
  const result = parseSubmissionResult(packet)
  if (!result) return
  onStudents({ maCodePhongThi: membership.maCodePhongThi, hocSinhList: [{ ...result, hoVaTen: packet.hoVaTen, maCodePhongThi: membership.maCodePhongThi }] })
}
socket.on('student_submitted', onSubmitted)
```

`useRoomPolling.mergeStudents()` dùng Map theo `idHs` nên event gửi lại không tăng số bài đã nộp hoặc tạo dòng trùng. Kết quả HOAN_THANH có điểm hợp lệ được giữ trước snapshot DANG_THI/MAT_KET_NOI cũ. `TeacherLiveLeaderboard` gọi `rankStudents()` và `roomStatistics()` mỗi lần dữ liệu đổi. Khi unmount, listener được gỡ bằng `socket.off('student_submitted', onSubmitted)`.

## Backend hỗ trợ tối thiểu cho UI

Các phần này cần để reload kết quả/bảng điểm và đóng phòng hoạt động thật; không thay đổi models hoặc triển khai Task 5.3:

| Hợp đồng | Nội dung |
| --- | --- |
| `GET /api/submissions/result?idHs=...&idPhongThi=...` | Pydantic query schema, kiểm tra cặp học sinh/phòng và trả kết quả đã commit; chưa chấm trả 409, không tìm thấy trả 404, thiếu metadata trả 503 |
| `GET /api/rooms/{id}` | Bổ sung `ketQuaList` gồm tên/ID/trạng thái/điểm thực tế; chỉ giáo viên sở hữu đọc được. Điểm bài chưa HOAN_THANH là null |
| Socket `close_exam_room` | Chỉ giáo viên đã join đúng phòng, kiểm tra lại JWT/quyền sở hữu; row lock và transaction cập nhật DA_DONG |
| Socket `room_closed` | Phát tới đúng phòng sau commit. Frontend giữ bảng điểm, khóa làm bài mới và dừng timer hiển thị của học sinh |

Schema tại `backend/app/schemas/submission.py` (`SubmissionLookupSchema`) và `backend/app/schemas/room.py` (`StudentScoreSchema`, `RoomClosedSchema`). API router chỉ nhận schema/gọi service. Service đọc điểm chỉ truy vấn theo room/student, trả DTO không có câu hỏi hoặc đáp án đúng.

Transaction close tại [room_lifecycle_service.py](../backend/app/services/room_lifecycle_service.py):

```python
try:
    # ... verify teacher ownership and acquire row lock ...
    if room.trang_thai_phong != TrangThaiPhongThi.DA_DONG:
        room.trang_thai_phong = TrangThaiPhongThi.DA_DONG
        db.flush()
        db.commit()
    return packet
except SQLAlchemyError as exc:
    db.rollback()
    raise HTTPException(status_code=503, detail="Unable to close the room. Please retry.") from exc
except Exception:
    db.rollback()
    raise
```

Mã đầy đủ tại service; handler chỉ broadcast sau khi service trả thành công. Close không chấm điểm 0 cho bài chưa nộp và không đổi điểm/trạng thái các bài đã HOAN_THANH. UI xác nhận rõ việc dừng nhận bài mới, nhãn bài chưa hoàn thành sau đóng là **CHƯA HOÀN THÀNH**. Học sinh mới bị từ chối join; kết quả đã chấm vẫn đọc và xuất được.

State Output Proof từ Socket.IO thật:

```json
{
  "status": "ok",
  "idPhongThi": "fixture-teacher-room",
  "maCodePhongThi": "AB12CD",
  "trangThaiPhong": "DA_DONG"
}
```

[room_closed_response.json](room_closed_response.json), [leaderboard_response.json](leaderboard_response.json) và [leaderboard_proof.log](leaderboard_proof.log) được ghi từ Uvicorn + Node Socket.IO client thật trên DB/runtime tạm. Snapshot phòng đóng chứa hai bài HOAN_THANH, điểm 6.67 và 3.33. Script đọc lại kết quả học sinh sau khi đóng, xác nhận giữ nguyên điểm, DB phòng DA_DONG và join mới trả 409. PostgreSQL dự án không bị ghi dữ liệu kiểm thử.

## Ảnh và kiểm chứng

- [Kết quả học sinh desktop](screenshots/task_5_2/student-result.png), [mobile 390px](screenshots/task_5_2/result-mobile.png).
- [Bảng điểm giáo viên desktop](screenshots/task_5_2/teacher-leaderboard.png), [mobile với tên dài](screenshots/task_5_2/leaderboard-mobile.png).
- Ảnh UI lấy từ Chrome với REST/Socket mock; log/JSON phía trên lấy từ backend và Socket.IO thật.
- Backend: toàn bộ **138 tests đạt**, gồm 8 ca hỗ trợ read/close mới.
- Frontend: **96 ca đã kiểm chứng** (84 ca trước + 12 leaderboard/result). Lượt toàn bộ có 95 ca đạt, một ca dùng nội dung footer phòng đóng cũ đã được cập nhật và chạy lại đạt. Sau thay đổi nhãn bài chưa hoàn thành, 36 ca liên quan (12 leaderboard, 17 teacher room, 7 recovery) chạy lại đạt.
- Production build thành công trên source frontend cuối. Mobile không có tràn ngang trang, điểm/nút/nhãn và tên dài đều hiển thị được.

Từ thư mục gốc, mỗi nhóm chạy trong terminal riêng:

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -m uvicorn main:app --reload --port 8000
```

```powershell
Set-Location .\frontend
npm.cmd run dev
```

Kiểm thử:

```powershell
Set-Location .\frontend
npm.cmd test
npm.cmd run build
```

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
..\.venv\Scripts\python.exe -B verify_socket_setup.py --leaderboard
```

Tiếp tục chạy một Uvicorn worker và giữ `backend/.runtime` để khôi phục số câu đúng/tổng câu theo Tasks 4.x/5.1. API kết quả lỗi hoặc thiếu dữ liệu không tạo điểm giả. `models.py` giữ nguyên SHA256:

```text
D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F
```

## Code đầy đủ StudentResult.jsx

```jsx
import { useEffect, useState } from 'react'
import { CheckCircle2, Home, LoaderCircle, RefreshCw, Trophy } from 'lucide-react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'
import FormMessage from '../components/FormMessage.jsx'
import { useStudentSession } from '../contexts/StudentSessionContext.jsx'
import { getStudentResult, resultError } from '../services/resultService.js'
import { formatScore, parseSubmissionResult, RESULT_MESSAGES } from '../utils/results.js'
import { normalizeRoomCode } from '../utils/studentValidation.js'

export default function StudentResult() {
  const { code } = useParams()
  const { session } = useStudentSession()
  const location = useLocation()
  const normalized = normalizeRoomCode(code)
  const valid = /^[A-Z0-9]{6}$/.test(normalized) && session?.maCodePhongThi === normalized
  const identity = `${session?.idHs}:${session?.idPhongThi}:${normalized}`
  const initial = location.state?.idPhongThi === session?.idPhongThi && location.state?.code === normalized ? parseSubmissionResult(location.state?.result, session?.idHs) : null
  const [state, setState] = useState({ identity, result: initial, loading: !initial, error: '' })
  const [version, setVersion] = useState(0)
  useEffect(() => {
    if (!valid) return
    const request = new AbortController()
    let disposed = false
    setState((current) => ({ identity, result: current.identity === identity ? current.result : null, loading: true, error: '' }))
    getStudentResult(session, request.signal).then((result) => {
      if (!disposed) setState({ identity, result, loading: false, error: '' })
    }).catch((error) => {
      if (!disposed && !request.signal.aborted) setState((current) => ({ ...current, loading: false, result: [404, 409, 401, 403].includes(error.response?.status) ? null : current.result, error: resultError(error) }))
    })
    return () => { disposed = true; request.abort() }
  }, [identity, valid, version])
  if (!valid) return <Navigate to="/join" replace state={{ notice: 'Hãy vào phòng thi để xem kết quả của bạn.' }} />
  if (code !== normalized) return <Navigate to={`/exam-room/${normalized}/result`} replace />
  const current = state.identity === identity ? state : { result: null, loading: true, error: '' }
  const result = current.result
  const message = result && RESULT_MESSAGES.find((item) => result.diemSo >= item.minimum)
  return (
    <section className="mx-auto w-full max-w-2xl overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-sm" aria-labelledby="student-result-title">
      <div className="border-b border-slate-100 px-5 py-6 sm:px-9"><p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">Kết quả bài thi · {normalized}</p><h1 id="student-result-title" className="mt-3 text-2xl font-bold tracking-tight">Kết quả của bạn</h1><p className="mt-3 break-words text-sm text-slate-500">{session.hoVaTen} · {session.tenLop}</p></div>
      <div className="px-5 py-9 text-center sm:px-9 sm:py-11">
        {current.error && <div className="mb-6 text-left"><FormMessage>{current.error}</FormMessage><button type="button" disabled={current.loading} onClick={() => setVersion((value) => value + 1)} className="button-secondary mt-3"><RefreshCw size={16} aria-hidden="true" />Tải lại kết quả</button></div>}
        {!result ? current.loading ? <p role="status" className="flex items-center justify-center gap-3 py-10 text-slate-500"><LoaderCircle size={22} className="animate-spin" aria-hidden="true" />Đang tải kết quả…</p> : <p className="py-8 text-sm text-slate-500">Chưa có điểm để hiển thị.</p> : <>
          <div className="mx-auto flex h-20 w-20 items-center justify-center rounded-full bg-sky-50 text-brand-700"><Trophy size={36} aria-hidden="true" /></div>
          <p className="mt-7 flex flex-wrap items-baseline justify-center gap-3" aria-label={`Điểm ${formatScore(result.diemSo)} trên 10`}><span data-testid="result-score" className="text-6xl font-bold tracking-tight text-brand-700 sm:text-7xl">{formatScore(result.diemSo)}</span><span className="text-xl font-medium text-slate-400">/ 10</span></p>
          <p data-testid="result-correct" className="mt-5 inline-flex items-center gap-2 rounded-full bg-emerald-50 px-4 py-2 text-sm font-semibold text-emerald-700"><CheckCircle2 size={17} aria-hidden="true" />{result.soCauDung}/{result.tongSoCau} câu đúng</p>
          <h2 className="mt-8 text-xl font-semibold">{message.title}</h2><p className="mx-auto mt-3 max-w-md text-sm leading-7 text-slate-500">{message.text}</p>
          {current.loading && <p role="status" className="mt-4 text-xs text-slate-400">Đang xác nhận lại kết quả…</p>}
        </>}
        <Link to="/" className="button-primary mt-8 inline-flex"><Home size={17} aria-hidden="true" />Quay về trang chủ</Link>
      </div>
    </section>
  )
}
```

## Code đầy đủ TeacherLiveLeaderboard.jsx

```jsx
import { CheckCircle2, Download, Trophy, UsersRound, WifiOff } from 'lucide-react'
import { formatScore, rankStudents, resultsCsv, roomStatistics } from '../../utils/results.js'

export default function TeacherLiveLeaderboard({ students, code, stale = false, closed = false }) {
  const rows = rankStudents(students)
  const stats = roomStatistics(students)
  function exportResults() {
    const url = URL.createObjectURL(new Blob([resultsCsv(students, closed)], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `ket-qua-${code}.csv`
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
  }
  return (
    <section aria-labelledby="leaderboard-title" className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 p-5 sm:px-6"><h2 id="leaderboard-title" className="inline-flex items-center gap-2 text-lg font-semibold"><Trophy size={20} className="text-brand-700" aria-hidden="true" />Bảng điểm trực tiếp</h2><button type="button" onClick={exportResults} disabled={!students.length} className="button-secondary !px-3 !py-2 !text-xs"><Download size={15} aria-hidden="true" />Xuất CSV</button></div>
      <dl className="grid grid-cols-3 divide-x divide-slate-100 border-b border-slate-100 py-5 text-center"><div><dt className="text-xs text-slate-500">Tổng thí sinh</dt><dd data-testid="leaderboard-total" className="mt-2 text-2xl font-bold">{stats.total}</dd></div><div><dt className="text-xs text-slate-500">Đã nộp</dt><dd data-testid="leaderboard-submitted" className="mt-2 text-2xl font-bold text-emerald-700">{stats.submitted}</dd></div><div><dt className="text-xs text-slate-500">Điểm trung bình</dt><dd data-testid="leaderboard-average" className="mt-2 text-2xl font-bold text-brand-700">{formatScore(stats.average)}</dd></div></dl>
      {!rows.length ? <div className="p-10 text-center text-sm text-slate-500"><UsersRound className="mx-auto mb-4 text-slate-300" size={32} aria-hidden="true" />Chưa có thí sinh trong phòng.</div> : <ol className="divide-y divide-slate-100" aria-live="polite" aria-relevant="additions text">
        {rows.map((student) => {
          const completed = student.trangThai === 'HOAN_THANH'
          const offline = !closed && student.trangThai === 'MAT_KET_NOI'
          return <li key={student.idHs} data-testid={`student-${student.idHs}`} className={`grid grid-cols-[24px_minmax(0,1fr)_auto] items-center gap-3 px-5 py-5 sm:px-6 ${completed ? 'bg-emerald-50/60' : offline ? 'bg-amber-50' : ''}`}><span className="text-sm font-semibold tabular-nums text-slate-400" aria-label={student.rank ? `Hạng ${student.rank}` : 'Chưa xếp hạng'}>{student.rank ?? '—'}</span><div className="min-w-0"><p className="break-words text-sm font-semibold text-slate-800">{student.hoVaTen}</p><span className={`mt-2 inline-flex items-center gap-1.5 rounded-full px-2 py-1 text-[10px] font-semibold sm:text-xs ${completed ? 'bg-emerald-100 text-emerald-800' : offline ? 'bg-amber-100 text-amber-800' : closed ? 'bg-slate-100 text-slate-600' : 'bg-sky-50 text-brand-700'}`}>{completed ? <CheckCircle2 size={13} aria-hidden="true" /> : offline ? <WifiOff size={13} aria-hidden="true" /> : null}{completed ? 'ĐÃ HOÀN THÀNH' : closed ? 'CHƯA HOÀN THÀNH' : offline ? 'MẤT KẾT NỐI' : 'ĐANG LÀM BÀI'}</span></div><span data-testid={`score-${student.idHs}`} className={`text-xl font-bold tabular-nums ${completed ? 'text-emerald-700' : 'text-slate-300'}`}>{completed ? formatScore(student.diemSo) : '—'}</span></li>
        })}
      </ol>}
      <p className="border-t border-slate-100 px-5 py-3 text-xs leading-5 text-slate-500 sm:px-6">{stale ? 'Đang hiển thị dữ liệu ở lần cập nhật gần nhất.' : closed ? 'Phòng đã đóng. Kết quả đã lưu vẫn được giữ lại.' : 'Điểm tự cập nhật khi thí sinh nộp bài. Trung bình tính trên các bài đã hoàn thành.'}</p>
    </section>
  )
}
```
