# Task 2.3 — Exam Dashboard Frontend

## Routes và giao diện

Tất cả route nằm trong `ProtectedRoute` và `TeacherLayout`:

| Route | Page | Chức năng |
| --- | --- | --- |
| `/exams` | `ExamsPage` | Kho cá nhân, tìm theo tên, xem/sửa/xóa |
| `/exams/create-ai` | `ExamCreateAIPage` | Nhập tài liệu, loading AI, preview và lưu |
| `/exams/:examId` | `ExamDetailPage` | Nội dung đề, bốn đáp án, đáp án đúng, nguồn |
| `/exams/:examId/edit` | `ExamEditPage` | Sửa tên/khối/câu hỏi và lưu thay đổi |

Component mới:
- `AiSourceForm`: tên đề, khối, số câu, textarea và chọn file `.txt`.
- `ExamEditor`: điều khiển bản nháp, thêm/xóa/sửa câu hỏi, nút lưu.
- `QuestionEditor`: nội dung và bốn phương án, radio chọn đáp án đúng.
- `QuestionCard`: trình bày câu hỏi và đánh dấu đáp án đúng ở trang chi tiết.
- `ExamCard`: tên, số câu, ngày, khối và thao tác trên mỗi đề.
- `ExamLoading`: skeleton khi tải danh sách/chi tiết.
- `GradeSelect`: khối 1–12 hoặc không phân khối.
- `ConfirmDialog`: xác nhận xóa hoặc bỏ bản nháp quay về tài liệu; focus/ESC
  được native dialog xử lý, khóa thao tác khi đang gửi request.

`services/examService.js` chứa mọi REST call và whitelist payload đúng
schema camelCase của backend. `hooks/useExamDetail.js` quản lý tải/abort/retry.
`utils/examValidation.js` kiểm tra input/câu hỏi và chuyển lỗi sang tiếng Việt.
Không triển khai tạo phòng thi hay bài làm học sinh.

## Luồng nhập → loading → kiểm duyệt → lưu

1. Mở “Tạo đề bằng AI”, nhập tên, khối và số câu (1–50).
2. Dán văn bản hoặc chọn `.txt` UTF-8 tối đa 1 MB. File được đọc ở trình
   duyệt và đưa vào textarea; gửi raw text tới backend. Không upload binary
   và không có parser PDF/DOCX. Tệp sai/không đọc được không xóa nguồn hiện có.
3. Bấm “Sinh đề bằng AI”: vô hiệu hóa form và hiện spinner/trạng thái chờ.
4. Response preview hiển thị từng câu với bốn đáp án A/B/C/D. Giáo viên sửa
   nội dung, phương án hoặc chọn đáp án đúng, thêm/xóa câu và sửa tên/khối.
5. Bấm “Lưu vào kho đề”: validate tất cả câu, gửi POST `/api/exams`, rồi mở
   trang chi tiết chỉ sau khi lưu thành công.
6. Kho đề hiển thị card tên/số câu/ngày, có tìm kiếm và thao tác xem/sửa/xóa.
   Xóa cần xác nhận và chỉ bỏ card sau khi API trả thành công; lỗi 409 giữ đề.

Preview là bản nháp chưa ghi DB. Bản nháp được giữ trong `sessionStorage`
theo ID giáo viên để tải lại/quay lại trang mà không gọi AI lần nữa. Bản nháp
được xóa khi lưu thành công hoặc xác nhận bỏ; tab storage hết hạn khi đóng tab.
Storage không khả dụng thì trang hiện tại vẫn giữ bản nháp trong memory.
Khi sửa đề có sẵn, lỗi lưu giữ nguyên nội dung đang chỉnh sửa; có cảnh báo
native khi reload/đóng tab nếu đã thay đổi dữ liệu.

## Endpoint preview hỗ trợ luồng này

`POST /api/exams/preview-ai` yêu cầu JWT và dùng `DeThiGenerateAISchema`.
Response là `DeThiCreateManualSchema`, chỉ có metadata + câu hỏi, không có
ID đề/câu hỏi. `preview_ai_exam` gọi Gemini qua service cô lập, validate và
trả DTO; không `db.add`, không INSERT/UPDATE và không commit. ERR_01 raise
được rollback transaction đọc/auth trước khi trả HTTP 503/400.

Endpoint `/generate-ai` của Task 2.2 vẫn giữ hành vi tạo và lưu ngay.
Frontend Task 2.3 gọi `/preview-ai`, rồi gọi API tạo thủ công khi giáo viên lưu.
Các câu hỏi chỉnh sửa được gửi chỉ với sáu key nội dung, không gửi ID/owner/count.
PUT đề có sẵn dùng toàn bộ danh sách câu hỏi theo hợp đồng Task 2.2.

Timeout backend vẫn là 15 giây; riêng request preview ở Axios chờ 20 giây
để nhận được lỗi backend sau xử lý/auth/network. Không tự động retry AI hoặc lưu.
ERR_01 timeout hiển thị thông báo tiếng Việt và giữ nguồn để người dùng thử lại.

## Proof: component chỉnh sửa

Mã hoàn chỉnh ở `frontend/src/components/exams/ExamEditor.jsx` và
`QuestionEditor.jsx`. Cập nhật câu hỏi dùng state bất biến:

```jsx
function updateQuestion(index, field, value) {
  onChange({
    ...draft,
    cauHoiList: draft.cauHoiList.map((question, position) =>
      position === index ? { ...question, [field]: value } : question),
  })
}
```

`QuestionEditor` gọi `onChange('noiDung', ...)`, `onChange('dapAnA', ...)`
(và B/C/D), radio gọi `onChange('dapAnDung', key)`; việc lưu thuộc page,
không nằm trong UI component.

## Kiểm tra và ảnh nghiệm thu

```powershell
Set-Location .\frontend
npm.cmd run build
npm.cmd test
```

Kết quả: build production đạt; **24 browser tests đạt** (11 auth + 13 exam).
Sau khi sửa thanh lưu để không che câu hỏi, build và 13 exam tests chạy lại đều đạt.
Kiểm thử Chrome mock REST API theo đúng schema, không gọi Google và không
ghi dữ liệu PostgreSQL. Các case gồm spinner, payload/Bearer, preview chưa
lưu, chỉnh sửa đáp án, chỉ lưu một lần, validation, upload UTF-8, timeout và
retry, khôi phục bản nháp, list/search/detail/edit/delete, xóa bị chặn và mobile.

Backend:

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
```

**46 backend tests đạt**, có ba test mới xác minh preview không add/commit,
không tạo đề/câu hỏi, rollback ERR_01 và validate trước truy vấn DB.
`models.py` không đổi; SHA-256:
`D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F`.

Ảnh chụp trong `docs/screenshots/task_2_3/` (dữ liệu kiểm thử mock):
- `create-ai.png`: form nguồn trước khi sinh.
- `loading-ai.png`: spinner và form bị khóa khi chờ.
- `review-edit.png`: preview, sửa nội dung và đổi đáp án đúng.
- `exam-library.png`: đề đã lưu xuất hiện trong kho.
- `library-mobile.png`, `editor-mobile.png`: màn hình 390 px, không tràn ngang.

## Chạy local

Khởi động backend theo hướng dẫn Task 2.2. Frontend:

```powershell
Set-Location .\frontend
npm.cmd run dev
```

Mở <http://localhost:5173>, đăng nhập giáo viên rồi chọn “Kho đề thi” hoặc
“Tạo đề bằng AI”. Backend cần JWT key, Gemini key và PostgreSQL hiện có.
