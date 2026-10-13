# Task 2.2 — Exam APIs Backend

Task 2.3 bổ sung `POST /api/exams/preview-ai` để trả bản nháp không ghi DB.
Xem [luồng Exam Dashboard](exam_dashboard.md) cho hợp đồng preview và lưu sau
kiểm duyệt; các API CRUD dưới đây giữ nguyên hành vi.

## Phạm vi và routes

Các endpoint đều yêu cầu JWT giáo viên (`Authorization: Bearer <token>`).
Router `backend/app/api/exam.py` chỉ nhận payload/dependency, gọi
`exam_service` và trả response qua Pydantic v2.

| Method | Endpoint | Thành công |
| --- | --- | --- |
| POST | `/api/exams/generate-ai` | 201, đề AI cùng câu hỏi |
| POST | `/api/exams` | 201, tạo thủ công |
| GET | `/api/exams` | 200, JSON array tóm tắt đề của giáo viên |
| GET | `/api/exams/{id_de_thi}` | 200, chi tiết và câu hỏi |
| PUT | `/api/exams/{id_de_thi}` | 200, thay toàn bộ đề/câu hỏi |
| DELETE | `/api/exams/{id_de_thi}` | 200, `{idDeThi, status: "deleted"}` |

Response JSON dùng camelCase theo `.rules.md`; schema ánh xạ sang
snake_case của ORM bằng alias generator. Input chấp nhận cả camelCase
và snake_case; key dư bị từ chối, không cho client tự đặt owner/ID/count.
Gemini vẫn trả sáu key snake_case theo hợp đồng Task 2.1.

POST thủ công được bổ sung để schema `DeThiCreateManualSchema` có endpoint
tạo đề tương ứng. PUT dùng cùng schema và là thao tác thay thế đầy đủ;
câu hỏi mới nhận ID mới, các câu hỏi cũ bị delete-orphan trong cùng transaction.
PUT không thay ID đề, owner hoặc ngày tạo. Không có frontend hoặc API phòng thi mới.

## Schema Verification Proof

`backend/app/schemas/exam.py` khai báo:

| Schema | Nội dung |
| --- | --- |
| `CauHoiCreateSchema` | Sáu field nội dung chuỗi; đáp án đúng A/B/C/D |
| `CauHoiResponseSchema` | Thêm `id_cau_hoi`, `id_de_thi` chuỗi |
| `DeThiCreateManualSchema` | Tên/khối/metadata tài liệu và ít nhất một câu hỏi |
| `DeThiGenerateAISchema` | Tên, khối, raw text, số lượng integer dương (mặc định 10) |
| `DeThiListResponseSchema` | Tóm tắt các cột model, không tải text/câu hỏi |
| `DeThiDetailResponseSchema` | Toàn bộ cột DeThi cùng `cau_hoi_list` |
| `ExamDeleteResponseSchema` | Xác nhận ID đề đã xóa |

ID trong model là `String`, nên response ID là `str`, không phải integer.
Nội dung/tên/khối/tài liệu là chuỗi. `so_luong_cau_hoi` là integer;
`ngay_tao` là `datetime.date`, serialize thành `YYYY-MM-DD`.
`khoi`/`noi_dung_tai_lieu` cho phép null. Hai cột count/date cũng nullable
trong model hiện có, nên response cho phép null để khớp model 100%.
Đề mới luôn được service gán count chính xác bằng số câu và ngày từ default model.

Schemas không thêm cột database. Test so sánh toàn bộ tập field của
`CauHoiResponseSchema` với cột `CauHoi`, và tập field chi tiết (trừ relationship)
với cột `DeThi`; kiểm tra kiểu integer/date/null cùng camelCase serialization.
Các phương án là chuỗi không rỗng và khác nhau sau khi trim/casefold.

## Transaction Safety Proof

`_write_transaction` trong `backend/app/services/exam_service.py`:

```python
try:
    yield
    db.commit()
except gemini_service.GeminiServiceError as exc:
    db.rollback()
    raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc
except IntegrityError as exc:
    db.rollback()
    raise HTTPException(status_code=409, detail="Exam data conflicts with existing records.") from exc
except SQLAlchemyError as exc:
    db.rollback()
    raise HTTPException(status_code=503, detail="Exam database unavailable.") from exc
except Exception:
    db.rollback()
    raise
```

`generate_ai_exam` gọi Gemini bên trong context này, validate toàn bộ N câu
trước khi tạo ORM objects, rồi gọi `_insert_exam`. Hàm insert gắn tất cả
`CauHoi` vào `DeThi.cau_hoi_list`, gọi `db.add(exam)` và `db.flush()`;
không có commit giữa parent và children. Pydantic response cũng được kiểm tra
trước commit. Context commit đúng một lần khi toàn bộ quy trình thành công.

Auth dependency và service dùng cùng Session qua `Depends(get_db)` cache.
Context không gọi `db.begin()` lại vì auth lookup đã có thể mở transaction
qua SQLAlchemy autobegin. Mọi write vẫn thuộc cùng transaction của Session.

ERR_01: Gemini timeout/API fail trả 503, JSON/schema sai trả 400 sau rollback;
không lưu đề trống, không commit một phần. Test gây lỗi sau INSERT câu hỏi
và tại commit, rồi truy vấn DB mới để xác nhận cả DeThi/CauHoi đều không còn.
Test lỗi update/delete cũng xác nhận dữ liệu cũ được phục hồi nguyên vẹn.

## Quyền sở hữu và toàn vẹn dữ liệu

- List chỉ SELECT đề theo `id_gv` hiện tại; không lộ đề giáo viên khác.
- Detail/update/delete lọc đồng thời owner và ID đề; không tồn tại hoặc khác
  owner đều trả 404. Thiếu/sai JWT trả 401.
- Update/delete khóa row đề bằng `SELECT FOR UPDATE` trên PostgreSQL.
- Đề đã được `PhongThi` tham chiếu trả 409 khi sửa/xóa; không làm thay đổi
  room, không phá foreign key hoặc cấu trúc model đã chốt.
- Xóa bằng `db.delete(exam)` để ORM áp dụng `cascade="all, delete-orphan"`
  hiện có; không dùng bulk DELETE bỏ qua cascade.
- Payload write được Pydantic validate trước truy vấn xác thực qua
  `core/exam_dependencies.py`. Test xác nhận payload sai trả 422 mà không
  gọi Gemini hoặc SELECT DB.

Tham chiếu ORM cascade: [SQLAlchemy](https://docs.sqlalchemy.org/en/20/orm/cascades.html).

## Payload mẫu

AI request có trong `docs/exam_generate_ai_request.json`.
Manual/PUT request:

```json
{
  "tenDeThi": "Kiến thức về nước",
  "khoi": "6",
  "kieuTaiLieu": "TEXT",
  "noiDungTaiLieu": "Nước có công thức H2O.",
  "cauHoiList": [
    {
      "noiDung": "Công thức hóa học của nước là gì?",
      "dapAnA": "H2O",
      "dapAnB": "CO2",
      "dapAnC": "O2",
      "dapAnD": "NaCl",
      "dapAnDung": "A"
    }
  ]
}
```

`khoi`, `noiDungTaiLieu` có thể null; `kieuTaiLieu` mặc định TEXT.
FILE chỉ là giá trị metadata cho tạo thủ công, chưa có chức năng upload/parse file.

## Chạy và kiểm thử

Từ thư mục gốc dự án:

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -m uvicorn main:app --reload --port 8000
```

Swagger: <http://127.0.0.1:8000/docs>. Dùng token của login để Authorize.
Tại terminal khác từ thư mục gốc, thay placeholder bằng JWT riêng:

```powershell
curl.exe -i -H "Authorization: Bearer <access_token>" -H "Content-Type: application/json" --data-binary "@docs/exam_generate_ai_request.json" http://127.0.0.1:8000/api/exams/generate-ai
```

Các test offline sử dụng database SQLite trong bộ nhớ, bật foreign keys và
mock Gemini. Không chỉnh sửa dữ liệu PostgreSQL:

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
```

Kết quả: **43 test backend đạt**, gồm 15 test Exam APIs, 12 auth và 16 Gemini.

Kiểm thử live PostgreSQL + Gemini và lấy response thực tế:

```powershell
..\.venv\Scripts\python.exe -B verify_exam_api.py
```

Script gọi các API hiện tại qua TestClient với JWT thật, dùng cùng schemas,
services và ORM; override Session để join savepoint của transaction ngoài.
Không tạo/sửa bảng hay schema PostgreSQL. Nó tạo hai tài khoản mẫu tạm thời,
sinh đề 2 câu bằng Gemini thật, xác minh dữ liệu đã INSERT, kiểm tra list/detail,
owner, update/delete và cascade; cuối cùng rollback transaction ngoài và
xác nhận không còn tài khoản/đề mẫu.

Kết quả live đã chạy: generation=201, list/detail=200, owner khác=404,
update=200, delete/cascade=200; tất cả bản ghi mẫu được rollback.
JSON response thành công nguyên vẹn lưu tại `docs/exam_generate_ai_response.json`.

`models.py` không đổi, SHA-256:
`D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F`.
