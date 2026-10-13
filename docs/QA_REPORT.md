# Task 5.3 — QA, UI Polish & Course Finalization

Nghiệm thu ngày 13/10/2026. Luồng E2E tích hợp dùng Chrome với hai browser context độc lập (giáo viên và học sinh ẩn danh/mobile), FastAPI/Socket.IO thật, Gemini API thật, PostgreSQL thật. Không mock API, Socket hoặc AI trong lần nghiệm thu này.

Script [backend/verify_e2e.py](../backend/verify_e2e.py) tạo schema `qa_e2e_<uuid>` riêng, chạy [verify-e2e.mjs](../frontend/scripts/verify-e2e.mjs), kiểm tra DB trực tiếp rồi dọn schema và server/Vite do nó tạo. Runtime cũng nằm trong thư mục tạm; dữ liệu ứng dụng hiện có và `models.py` được giữ nguyên. [e2e_results.json](qa/e2e_results.json) là bản ghi cuối thành công.

## Checklist tám bước

| # | Luồng | Kết quả | Bằng chứng |
| --- | --- | --- | --- |
| 1 | Đăng ký/đăng nhập giáo viên → JWT | PASS | Form thật, token 3 phần, `/auth/me` hợp lệ và không có mật khẩu; DB lưu bcrypt. |
| 2 | Upload text → Gemini JSON → lưu DB | PASS | Upload `.txt` UTF-8; AI sinh 3 câu thật, preview không ghi DB; lưu đề cùng 3 câu vào PostgreSQL. [JSON preview](qa/gemini_preview.json), [loading](qa/ai-loading.png), [review](qa/ai-review.png). |
| 3 | Tạo phòng → mã 6 ký tự | PASS | Mã khớp `[A-Z0-9]{6}`, trạng thái `DANG_CHO`; constraint UNIQUE và retry collision được kiểm tra thêm trong backend tests. |
| 4 | Học sinh mobile/ẩn danh nhập mã | PASS | Context riêng 390×844/touch, chỉ họ tên+mã; không teacher JWT; `sessionStorage` lưu định danh. [Join](qa/student-join.png), [Waiting](qa/student-waiting.png). |
| 5 | Start → câu hỏi an toàn → countdown | PASS | `DANG_THI`, 3 câu/12 radio; kiểm tra đệ quy payload không có `dapAnDung`/`dap_an_dung`; countdown giảm thật và lệch hai màn hình ≤1 giây. [Payload](qa/exam_started.json), [Taking](qa/student-taking.png). |
| 6 | Đóng tab học sinh → mất kết nối | PASS | Tab Chrome được đóng thật; DB và event chuyển `MAT_KET_NOI`; giáo viên thấy nhãn cam. [Giáo viên](qa/teacher-disconnected.png). |
| 7 | Khôi phục tab/cùng phiên → phục hồi bài | PASS, cùng phiên | Tái mở với sessionStorage của tab cũ được phục hồi như Restore Closed Tab; tiếp tục F5 thật. DB trở lại `DANG_THI`, giữ 2 đáp án, giữ nguyên `endsAt`, không cấp thêm thời gian. [Recovered](qa/student-recovered.png). |
| 8 | Nộp → điểm /10 → Live Leaderboard | PASS | 2/3 đúng → `6.67`, `HOAN_THANH`; nhận `student_submitted`, giáo viên cập nhật điểm/số bài/điểm trung bình; F5 học sinh đọc lại cùng điểm. [Result](qa/student-result.png), [Leaderboard](qa/teacher-leaderboard.png). |

Kiểm tra bổ sung trong E2E: tải được CSV [results.csv](qa/results.csv); đóng phòng lưu `DA_DONG` và giữ điểm; học sinh về trang chủ kết thúc phiên cũ. Kết quả database cuối cùng:

```json
{
  "engine": "PostgreSQL",
  "passwordHashed": true,
  "questionCount": 3,
  "studentCount": 1,
  "diemSo": 6.67,
  "trangThai": "HOAN_THANH",
  "trangThaiPhong": "DA_DONG"
}
```

## UI/UX và console

- Bốn trang học sinh join/waiting/taking/result kiểm tra ở 320, 390 và 768 px: 12 tổ hợp, không tràn ngang; chụp ảnh full-page 390 px và kiểm tra trực quan màn hình làm bài.
- Form/radio vẫn thao tác được trong context touch. Văn bản và tên lớp dài có `break-words`, bố cục một cột trên mobile, countdown tách rõ với câu hỏi.
- Sửa cảnh báo React key spread: `useExamSocket` loại bỏ `key` nhận diện nội bộ trước khi các trang spread props vào `SocketConnectionStatus`.
- Hoãn mở transport Socket một microtask để bỏ qua effect bị StrictMode cleanup ngay lập tức; tránh mở rồi hủy WebSocket trong lúc đang handshake.
- Bổ sung favicon SVG; sửa title/description dùng chung cho giáo viên và học sinh, loại bỏ request favicon 404.
- Bỏ màn hình receipt cũ không thể truy cập trong `StudentTaking`; kết quả dùng đúng `StudentResult`.
- Khi phòng chờ đóng, hiển thị kết thúc và dừng animation. Khi về trang chủ từ kết quả, xóa phiên sau điều hướng để tránh race giữa chuyển trang và guard.
- Lần E2E cuối: `browserErrors: []`, `browserWarnings: []`. Không có React key/hydration warning; không có `console.log`/`console.debug` trong `frontend/src`. App dùng `createRoot`, không SSR/hydration.
- Backend đọc `backend/.env` ngay trong `database.py`, bảo đảm `init_db.py` và Uvicorn dùng cùng cấu hình độc lập với cwd/import order.

## Kiểm thử tự động và build

| Kiểm tra | Kết quả |
| --- | --- |
| Backend unittest | 138/138 PASS, 24.215 giây |
| Chrome E2E thực | 8/8 PASS + đóng phòng/CSV, schema PostgreSQL đã dọn |
| Frontend Playwright | 97/97 PASS, 4.3 phút; [summary](qa/frontend_test_results.json), [log](qa/frontend_tests.log) |
| Vite production build | PASS; 1.738 modules, JS 394.32 kB (gzip 122.30 kB), CSS 27.83 kB |
| Schema bảo toàn | SHA256 `d225dca61619e02e2697b4caed497e3ebed6badbfa2d771b37cf02750721530f` giữ nguyên |

Unit/integration tests sử dụng fixtures và UI tests thông thường mock API/Socket: kiểm tra ERR_01 timeout/JSON, rollback, quyền sở hữu, input 422, cascade, disconnect nhiều socket, khôi phục sau deadline, submission retry/race, không phát điểm trước commit, đồng điểm/CSV và đóng phòng. Không dùng số lượng unit test để thay thế bằng chứng E2E thật.

Terminal unittest có cảnh báo deprecation của thư viện Starlette/TestClient; Playwright có cảnh báo biến màu `NO_COLOR`/`FORCE_COLOR` từ môi trường runner. Đây không phải lỗi React trên trình duyệt. Không thay dependency để che cảnh báo trong phạm vi QA này.

## Phạm vi chứng minh và giới hạn

Chrome mô phỏng viewport/touch không chứng minh tương thích thiết bị iOS/Android vật lý. Chưa load-test, kiểm thử nhiều worker hoặc triển khai production.

Khôi phục tab ở bước 7 được driver khôi phục **sessionStorage của tab cũ** để mô phỏng browser Restore Closed Tab, rồi kiểm tra F5 thật. Không tuyên bố tab mới bất kỳ tự khôi phục danh tính: sessionStorage bị mất khi đóng tab nếu trình duyệt không phục hồi. LocalStorage chỉ lưu đáp án theo run; mở tab mới không có phiên sẽ về `/join`. Không thay hợp đồng sessionStorage bằng lưu định danh toàn trình duyệt.

Chạy một worker và giữ `.runtime`. Điểm DB được bảo toàn khi runtime thiếu nhưng chi tiết kết quả có thể trả 503. Học sinh ẩn danh được kiểm tra bằng ID/phòng, chưa có admission token riêng. Chính sách hiện tại nhận bài nộp muộn do offline, đánh dấu `receivedLate`; đóng phòng không chấm bài chưa nộp.

Các lần phát triển script trước nghiệm thu phát hiện lỗi điều kiện đợi URL/tên nút trong driver và race về trang chủ; đã được sửa. Lần cuối là kết quả trong `e2e_results.json`, không lấy dữ liệu từ những lần thử chưa hoàn tất. Gemini là dịch vụ ngoài nên lần chạy sau vẫn phụ thuộc key/model/mạng/quota và giới hạn 15 giây.

## Tài liệu bàn giao

[README.md](../README.md) có cài đặt từ A–Z, biến môi trường, tạo database/bảng, hai lệnh khởi chạy, test và xử lý lỗi. [API_DOCUMENTATION.md](API_DOCUMENTATION.md) mô tả đủ 16 REST operations và các sự kiện Socket; [OpenAPI snapshot](qa/openapi.json) được sinh từ ứng dụng hiện tại. [COURSE_REPORT.md](COURSE_REPORT.md) có kiến trúc, ER diagram, luồng thi, bằng chứng và kịch bản demo.
