# Task 5.1 — Submission & Auto-Grading Backend

Đã thêm `POST /api/submissions/submit`, chấm điểm theo thang 10 và cập nhật `KetQuaThi` sang HOAN_THANH. Socket `submit_exam` dùng cùng service. Thông báo `student_submitted` chỉ phát tới giáo viên sở hữu phòng sau commit. Giao diện điểm/leaderboard và API đọc lại kết quả hiện đã có ở [Task 5.2](leaderboard.md).

## Các file chính

| File | Trách nhiệm |
| --- | --- |
| `backend/app/schemas/submission.py` | Hợp đồng request, result, metadata nội bộ và thông báo giáo viên |
| `backend/app/services/grading_service.py` | Kiểm tra phòng/học sinh, chấm điểm, lưu transaction, chống đổi điểm khi retry và phát thông báo |
| `backend/app/api/submission.py` | Router mỏng nhận schema, gọi service, trả response model |
| `backend/main.py` | Đăng ký router dưới `/api` |
| `backend/app/core/socket_manager.py` | Nối `submit_exam` tới cùng service chấm điểm |
| `backend/app/core/exam_runtime.py` | Lưu kết quả tổng hợp để trả đúng số câu/điểm khi retry |
| `backend/app/services/exam_flow_service.py` | Chỉ trả receipt trong đồng bộ khi DB đã HOAN_THANH |
| `backend/tests/test_submission.py` | 17 ca kiểm thử mới |
| `frontend/scripts/verify-submission.mjs` | Client kiểm chứng REST + Socket.IO thật; không sửa UI |

## Schema Verification Proof

Code đầy đủ [schemas/submission.py](../backend/app/schemas/submission.py):

```python
"""Strict Pydantic v2 contracts; camelCase JSON, snake_case ORM attributes."""

from typing import Annotated, Literal, Optional

from pydantic import Field, model_validator

from app.models.models import TrangThaiBaiLam
from app.schemas.room import NonEmptyText, RoomCode, RoomSchema


class AnswerItem(RoomSchema):
    id_cau_hoi: NonEmptyText
    dap_an_chon: Optional[Literal["A", "B", "C", "D"]]


class ExamSubmissionSchema(RoomSchema):
    id_hs: NonEmptyText
    id_phong_thi: NonEmptyText
    danh_sach_cau_tra_loi: list[AnswerItem]

    @model_validator(mode="after")
    def unique_question_ids(self):
        ids = [answer.id_cau_hoi for answer in self.danh_sach_cau_tra_loi]
        if len(ids) != len(set(ids)):
            raise ValueError("Question IDs must not be duplicated.")
        return self


class SubmissionLookupSchema(RoomSchema):
    id_hs: NonEmptyText
    id_phong_thi: NonEmptyText


class SubmissionResultResponseSchema(RoomSchema):
    id_hs: str
    diem_so: Annotated[float, Field(ge=0, le=10, allow_inf_nan=False)]
    so_cau_dung: Annotated[int, Field(ge=0)]
    tong_so_cau: Annotated[int, Field(ge=1)]
    trang_thai: TrangThaiBaiLam

    @model_validator(mode="after")
    def valid_result(self):
        if self.trang_thai != TrangThaiBaiLam.HOAN_THANH:
            raise ValueError("A grading result must be HOAN_THANH.")
        if self.so_cau_dung > self.tong_so_cau:
            raise ValueError("Correct count exceeds the question count.")
        return self


class StoredGradeSchema(RoomSchema):
    id_phong_thi: NonEmptyText
    run_id: NonEmptyText
    result: SubmissionResultResponseSchema


class StudentSubmittedEventSchema(SubmissionResultResponseSchema):
    id_phong_thi: str
    ma_code_phong_thi: RoomCode
    ho_va_ten: str
```

`RoomSchema` dùng Pydantic v2 `ConfigDict(strict=True, extra='forbid', from_attributes=True, alias_generator=to_camel, populate_by_name=True)`. JSON dùng camelCase; API cũng nhận snake_case như mô tả task. ID khớp String của models; `diem_so` khớp Float; `trang_thai` dùng đúng Enum TrangThaiBaiLam. Hai số đếm là int tính từ câu hỏi thực tế, không thêm cột DB.

`dapAnChon: null` hoặc câu hỏi bị bỏ qua tính là sai; thiếu trường `dapAnChon`, chọn ngoài A/B/C/D, thiếu ID, field thừa hoặc ID câu hỏi trùng bị trả 422 trước SQL. Mảng rỗng hợp lệ và cho điểm 0.0. Câu hỏi không thuộc đề của phòng bị trả 422 trong service.

## Transaction Safety Proof

[grading_service.grade_submission()](../backend/app/services/grading_service.py) truy vấn KetQuaThi/PhongThi/HocSinh bằng cả ID học sinh, ID phòng và quan hệ mã phòng, dùng `with_for_update()`. Truy vấn đáp án đúng chỉ theo `CauHoi.id_de_thi == room.id_de_thi`. Không dùng số lượng cache trên DeThi để tính mẫu số; đề không có câu hỏi bị chặn 409, đáp án DB sai định dạng bị chặn 503.

Trích code trong cùng khối `try`:

```python
so_cau_dung = sum(
    stored.answers.get(question_id) == correct
    for question_id, correct in correct_answers.items()
)
tong_so_cau = len(correct_answers)
diem_so = round((so_cau_dung / tong_so_cau) * 10, 2)

# ... validate response and prepare private metadata ...
result_row.diem_so = diem_so
result_row.trang_thai = TrangThaiBaiLam.HOAN_THANH
db.flush()
db.commit()
```

Các nhánh lỗi:

```python
except HTTPException:
    db.rollback()
    raise
except (SQLAlchemyError, OSError, ValueError) as exc:
    db.rollback()
    raise HTTPException(
        status_code=503,
        detail="Unable to save the exam score. Please retry.",
    ) from exc
except Exception:
    db.rollback()
    raise
```

Test ép lỗi commit và lỗi sau flush xác nhận cả điểm/trạng thái vẫn là 0.0/DANG_THI và không có thông báo nộp thành công. Receipt chuẩn bị trước commit không xuất hiện trong sync khi DB chưa HOAN_THANH. Metadata kết quả chỉ được dùng làm response retry khi DB thực sự đã HOAN_THANH và điểm trùng dữ liệu lưu.

## State Output Proof — REST và Socket.IO thật

Request dùng fixture ba câu, đáp án đúng của fixture lần lượt A/B/C:

```json
{
  "idHs": "fixture-student",
  "idPhongThi": "fixture-teacher-room",
  "danhSachCauTraLoi": [
    {"idCauHoi": "fixture-teacher-q-1", "dapAnChon": "A"},
    {"idCauHoi": "fixture-teacher-q-2", "dapAnChon": "B"},
    {"idCauHoi": "fixture-teacher-q-3", "dapAnChon": null}
  ]
}
```

Response thực tế của `POST /api/submissions/submit`, HTTP 200:

```json
{
  "idHs": "fixture-student",
  "diemSo": 6.67,
  "soCauDung": 2,
  "tongSoCau": 3,
  "trangThai": "HOAN_THANH"
}
```

Event `student_submitted` tới `teachers:AB12CD`:

```json
{
  "idHs": "fixture-student",
  "diemSo": 6.67,
  "soCauDung": 2,
  "tongSoCau": 3,
  "trangThai": "HOAN_THANH",
  "idPhongThi": "fixture-teacher-room",
  "maCodePhongThi": "AB12CD",
  "hoVaTen": "Nguyễn Văn An"
}
```

Payload gốc: [submission_response.json](submission_response.json), [student_submitted_response.json](student_submitted_response.json). Log [submission_proof.log](submission_proof.log) lấy từ Uvicorn và Node Socket.IO client thật. Script đọc lại DB, xác nhận HOAN_THANH/6.67; gửi lại với câu trả lời khác vẫn trả cùng kết quả. Một học sinh thứ hai nộp qua Socket được chấm 1/3 = 3.33 và giữ cùng receipt/điểm khi retry. Học sinh và giáo viên phòng khác không nhận event riêng của chủ phòng; response/event không chứa đáp án đúng.

## Hành vi retry và phạm vi

- Không yêu cầu tài khoản học sinh. Service xác minh đúng cặp ID học sinh/phòng đã được tạo qua anonymous join; không tạo KetQuaThi mới khi chấm.
- Phòng DANG_CHO không nhận bài. Phòng DANG_THI nhận bài kể cả khi kết quả trước đó là MAT_KET_NOI. Phòng DA_DONG chỉ chấm snapshot đã được server tiếp nhận trước đó, không nhận bài mới.
- Bài đầu tiên đã lưu trên server được dùng làm nguồn chấm, kể cả receipt từ Task 4.2. Retry không cho đổi điểm hoặc đáp án đã khóa. HOAN_THANH chỉ trả kết quả cũ và không bị disconnect/reconnect chuyển lại trạng thái khác.
- Tiếp tục hỗ trợ bài tự nộp được gửi trễ do mạng/khôi phục: vẫn áp dụng đúng công thức, không tự trừ điểm; `receivedLate` giữ trong receipt để kiểm toán. Endpoint hiện không áp dụng chính sách loại bài trễ.
- Backend Socket ACK giữ `submission` tương thích và thêm `result`. Từ Task 5.2, frontend hiển thị điểm ngay, đọc lại kết quả khi reload và cập nhật leaderboard giáo viên.
- Điểm/trạng thái nằm trong PostgreSQL. Do models không có cột số câu đúng/tổng câu hoặc đáp án đã nộp, các số đếm và snapshot lưu trong runtime JSON riêng. Nếu thiếu metadata của bài đã HOAN_THANH, API trả 503 và giữ nguyên điểm, không chấm lại theo payload mới.
- Khi transport thông báo lỗi sau commit, điểm vẫn được giữ; lỗi được ghi log. Retry gửi lại cùng kết quả và phát lại event. Socket.IO hiện không có outbox bảo đảm giao thông báo đúng một lần.
- Mọi SQL ở service chạy trong worker thread, không chạy trên Socket event loop. REST và Socket dùng cùng lock theo phòng và cùng row lock DB, phối hợp với disconnect/reconnect/sync.

## Kiểm chứng và chạy local

**130 backend tests đạt**, gồm 17 ca mới cho grading và hồi quy toàn bộ các task trước. Kiểm chứng `verify_socket_setup.py --grading` đạt với REST thật, Socket.IO thật và DB/runtime tạm. PostgreSQL dự án không bị ghi dữ liệu kiểm thử. Không thay đổi dependency hay source UI frontend.

Từ thư mục gốc:

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
..\.venv\Scripts\python.exe -B verify_socket_setup.py --grading
```

Khởi chạy backend:

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -m uvicorn main:app --reload --port 8000
```

Ví dụ PowerShell kiểm thử local (thay ID fixture bằng ID thật của phòng đang thi):

```powershell
$submissionBody = @{
  idHs = "<id_hs_tu_join>"
  idPhongThi = "<id_phong_thi>"
  danhSachCauTraLoi = @(
    @{ idCauHoi = "<id_cau_hoi>"; dapAnChon = "A" }
  )
} | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/api/submissions/submit" -ContentType "application/json" -Body $submissionBody
```

Tiếp tục dùng một Uvicorn worker và giữ `backend/.runtime` như Tasks 4.2/4.3. `backend/app/models/models.py` giữ nguyên SHA256:

```text
D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F
```
