# Task 2.1 — Gemini AI Service Integration

## Phạm vi

Toàn bộ prompt, SDK call, timeout, parse và kiểm tra output được cô lập tại
`backend/app/services/gemini_service.py`. Schema Pydantic v2 chỉ khai báo
các trường/kiểu dữ liệu tại `backend/app/schemas/quiz.py`.
Service không import Session, không ghi DB và không tạo router Task 2.2.
`models.py` giữ nguyên.

## Cấu hình

Thêm vào `backend/.env` (đã được loại khỏi Git):

```dotenv
GEMINI_API_KEY=<private-api-key>
GEMINI_MODEL=gemini-3.5-flash-lite
```

Cũng hỗ trợ `GOOGLE_API_KEY`. Biến môi trường có ưu tiên cao hơn `.env`.
Service đọc lại `.env` khi gọi, không cần import config JWT hoặc restart
để nhận key mới từ file. Không đặt key trong frontend, `VITE_*`, URL hoặc log.
Model mặc định dùng một phiên bản ổn định, có thể ghi đè bằng `GEMINI_MODEL`.

Tham chiếu: [Gemini models](https://ai.google.dev/gemini-api/docs/models),
[Structured outputs](https://ai.google.dev/gemini-api/docs/structured-output),
[Google GenAI SDK](https://googleapis.github.io/python-genai/).

## Hợp đồng gọi hàm

```python
from app.services.gemini_service import generate_quiz_from_text

questions = generate_quiz_from_text(raw_text, num_questions=10)
```

Đây là hàm đồng bộ, phù hợp với router `def`/service đồng bộ hiện có.
Khi được tích hợp trong router `async def` ở Task 2.2, cần gọi qua
`await asyncio.to_thread(generate_quiz_from_text, raw_text, num_questions)`
để không block event loop. Service từ chối gọi trực tiếp trong event loop.

Đầu ra là `List[dict]`, có thể serialize thành JSON Array. Mỗi phần tử có
đúng sáu field chuỗi của `CauHoi`: `noi_dung`, `dap_an_a`, `dap_an_b`,
`dap_an_c`, `dap_an_d`, `dap_an_dung`. Không sinh ID hoặc khóa ngoại.
Các field đều bắt buộc, không rỗng; bốn phương án phải khác nhau sau khi
trim và casefold; đáp án đúng là một trong A/B/C/D. Không nhận key dư,
key trùng trong JSON, object bọc ngoài, Markdown, sai kiểu hoặc sai số lượng.
Nếu một phần tử sai, toàn bộ kết quả bị từ chối; không trả array một phần.

System prompt yêu cầu chỉ dựa trên nguồn và không làm theo chỉ dẫn nằm trong
tài liệu. `response_mime_type="application/json"` và JSON Schema array
được gửi tới Gemini. Pydantic kiểm tra lại kết quả tại server; việc khớp
schema không tự chứng minh nội dung/đáp án do AI sinh là chính xác về kiến thức.

## ERR_01 và timeout

SDK async chạy bên trong `asyncio.run`. `asyncio.wait_for(..., timeout=15)`
giới hạn toàn bộ lần gọi SDK, hủy coroutine khi hết hạn; timeout HTTP là
15000 ms và retry attempts=1 để không tự phát sinh lượt gọi lại.
Cả client async và sync được đóng sau thành công/thất bại/timeout.

| Ngoại lệ | HTTP dự kiến khi tích hợp | Tình huống |
| --- | --- | --- |
| `GeminiTimeoutError` | 503 | Deadline 15s, transport timeout, API 408/504 |
| `GeminiServiceError` | 503 | Thiếu cấu hình, lỗi kết nối/SDK/API |
| `GeminiResponseError` | 400 | JSON/schema/số lượng sai, blocked hoặc truncated |
| `GeminiInputError` | 400 | Nguồn trống/sai kiểu hoặc số câu không hợp lệ |

Tất cả kế thừa `GeminiServiceError`, có `error_code="ERR_01"` và
`status_code`. Thông báo lỗi không chứa provider response hoặc API key.
Service raise exception; không trả list rỗng để giả vờ thành công.

Ví dụ **hợp đồng tích hợp cho Task 2.2**, chưa thêm vào router thực tế:

```python
try:
    questions = generate_quiz_from_text(raw_text, num_questions)
    # Chỉ sau thành công mới tạo DeThi/CauHoi trong cùng transaction.
except GeminiServiceError as exc:
    db.rollback()
    raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc
```

Không có transaction trong Task 2.1 để tự rollback: trách nhiệm này thuộc
tầng gọi giữ Session. Việc raise đảm bảo caller không tiếp tục lưu dữ liệu lỗi.

## Kiểm thử và output thực tế

Từ thư mục gốc dự án trên PowerShell:

```powershell
Set-Location .\backend
..\.venv\Scripts\python.exe -B -m unittest discover -s tests -p test_gemini_service.py -v
..\.venv\Scripts\python.exe -B verify_gemini.py
```

Unit tests dùng mock SDK, không gọi Google hoặc ghi DB. Test deadline thực
chờ đủ 15 giây và xác minh coroutine bị hủy cùng client được đóng.
Các test khác kiểm tra structured-output config, contract sáu key, lỗi JSON,
kiểu dữ liệu, nullable bị từ chối, key dư/trùng, bốn phương án khác nhau,
count/default 10, blocked/truncated response, API/transport errors và config.

`verify_gemini.py` chỉ gọi hàm với một câu hỏi từ `docs/gemini_test_input.txt`.
Khi thành công, script in JSON Array thực tế và lưu nguyên output đã validate
tại `docs/gemini_live_output.json`. Nếu thiếu key/timeout/lỗi API/schema,
script trả exit code 1 và không ghi output mới.

Output live cần API key hợp lệ và kết nối Gemini. Không dùng kết quả mock
để thay thế bằng chứng Gemini thực tế hoặc đánh dấu nghiệm thu live.

Kết quả nghiệm thu đã chạy: **16 unit tests đạt**, gồm kiểm thử deadline
15 giây thực; `pip check` không phát hiện dependency lỗi. Gemini API đã
được gọi thực tế bằng đoạn văn test về nước, `num_questions=1`; output
hợp lệ đã được lưu trong `gemini_live_output.json`. Lỗi encoding console
Windows của script nghiệm thu đã được sửa sang UTF-8; việc in output được
kiểm tra lại bằng kết quả live đã lưu mà không phát sinh request Google mới.

Không thay đổi router hoặc models trong Task 2.1. SHA-256 của `models.py`
giữ nguyên: `D225DCA61619E02E2697B4CAED497E3EBED6BADBFA2D771B37CF02750721530F`.
