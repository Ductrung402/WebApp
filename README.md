# EZam

Web tạo đề trắc nghiệm bằng AI, kiểm duyệt câu hỏi và tổ chức thi trực tuyến.
Backend hiện tại dùng **Java Servlet**, Frontend dùng **React**.

## Mã nguồn

| Vị trí | Nội dung |
| --- | --- |
| `backend/src/main/java/com/ezam/` | Servlet, DTO, Service, DAO, Model, Filter và tiện ích |
| `backend/src/main/webapp/WEB-INF/web.xml` | Cấu hình Servlet 4.0 và Filter |
| `backend/src/test/` | JUnit, fixture tổng hợp và probe kiểm thử |
| `frontend/src/`, `frontend/public/` | Giao diện React và asset |
| `frontend/tests/`, `frontend/scripts/` | Kiểm thử và script kiểm chứng |
| `scripts/`, `start_app.bat` | Khởi động Backend và Frontend trên Windows |

## Chuẩn bị

- JDK **17**, Apache Tomcat **9.0.x**, Node **22.14.0/npm**, PostgreSQL.
- Maven Wrapper có sẵn; lần tải dependencies đầu tiên cần Internet.
- Backend dùng JDBC/HikariCP, không dùng Spring/JPA/FastAPI. Không dùng Tomcat 10.
- Cần database hiện hữu với sáu bảng `giao_vien`, `de_thi`, `cau_hoi`,
  `phong_thi`, `hoc_sinh`, `ket_qua_thi`. Java không tự tạo/migrate schema.
  Fixture SQL trong test chỉ dành cho database kiểm thử riêng.

## Cấu hình và chạy local

Từ thư mục gốc, cài dependencies Frontend:

```powershell
npm.cmd --prefix frontend ci
```

Đặt `JAVA_HOME` tới JDK 17 và `CATALINA_HOME` tới Tomcat 9 trong terminal chạy Backend.
Tham khảo [backend/.env.java.example](backend/.env.java.example) để cấu hình
`DATABASE_URL`, `JWT_SECRET_KEY`, `GEMINI_API_KEY`, `GEMINI_MODEL`,
`SOCKET_PORT` và `RUNTIME_DIRECTORY` bằng biến môi trường hoặc `backend/.env` cục bộ.
Không ghi đè cấu hình riêng đã có. JWT secret cần ít nhất 32 byte; key Gemini phải
có quyền dùng model được cấu hình. Thư mục runtime cần được giữ bền vững, nằm ngoài `target`.

Mở hai terminal tại thư mục gốc:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start_backend.ps1
```

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start_frontend.ps1
```

Hoặc dùng `start_app.bat` sau khi đặt cấu hình. Mặc định: Frontend `http://localhost:5173`,
HTTP API `http://localhost:8080`, Socket.IO `http://localhost:8001`.
Script Frontend đặt các URL này cho Vite. Nếu chạy Vite riêng, cấu hình theo
[frontend/.env.example](frontend/.env.example) rồi khởi động/build lại.
Launcher đọc `.env` theo whitelist; Backend chạy trực tiếp trong Tomcat chỉ đọc process env.

## Build và kiểm thử

```powershell
Push-Location backend
.\mvnw.cmd verify
Pop-Location
npm.cmd --prefix frontend run build
npm.cmd --prefix frontend test
```

Bare Maven verify kiểm tra unit và đóng gói WAR, không mặc nhiên chạy mọi PostgreSQL/Gemini
integration. Các test ghi DB phải dùng môi trường riêng qua
`backend/src/test/fixtures/run_postgres_tests.py`; Python chỉ hỗ trợ điều phối kiểm thử.
Playwright UI tests dùng Chrome và mock API/Socket; không thay thế E2E thật.

Các tệp `.env`, dữ liệu runtime, dependencies, build output và tài liệu cá nhân không được
đưa vào nhánh source. Không đặt secret vào biến `VITE_*`, vì chúng công khai trong bundle.

## Public ứng dụng trên Railway (Java Servlet)

Repository: `Ductrung402/WebApp`, nhánh triển khai: **`Ezam_Servlet`**.
Backend này dùng Tomcat 9/Java 17; không dùng lại lệnh chạy Uvicorn của bản Python.

Mô hình dùng một domain công khai và Caddy có sẵn trong Frontend:

```text
Trình duyệt --HTTPS--> frontend (Caddy :8080)
                       /api/*       --> backend.railway.internal:8080 (Tomcat)
                       /socket.io/* --> backend.railway.internal:8001 (Socket.IO)
                                        |
                                        +--> PostgreSQL nội bộ
                                        +--> Volume runtime
```

### 1. Chuẩn bị PostgreSQL

Trong cùng project/environment Railway, dùng PostgreSQL hiện hữu của EZam nếu đã có
đúng sáu bảng. Nếu tạo PostgreSQL mới, cần nhập schema/backup của database EZam trước
khi dùng ứng dụng. **Backend Java không tự tạo bảng.** Không dùng
`backend/src/test/resources/dao/schema.sql` để khởi tạo production; file đó phục vụ test.
Sao lưu trước khi nhập dữ liệu; không ghi đè database cũ đang hoạt động.

### 2. Tạo service `backend`

1. Thêm service từ GitHub repo `Ductrung402/WebApp`, chọn nhánh `Ezam_Servlet`.
2. Đặt tên service là `backend`, Root Directory là `/backend`.
3. Dùng Dockerfile có sẵn trong thư mục đó. Để trống Custom Build Command và
   Custom Start Command để sử dụng cấu hình của image Tomcat.
4. Thêm Variables dưới đây. Cú pháp `${{Postgres.DATABASE_URL}}` giả định service
   database tên `Postgres`; chọn Reference Variable tương ứng nếu tên khác.

| Biến Backend | Giá trị |
| --- | --- |
| `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` (URL mạng nội bộ của PostgreSQL) |
| `JWT_SECRET_KEY` | Giá trị ngẫu nhiên riêng cho production, ít nhất 32 byte; chỉ nhập trong Railway |
| `ACCESS_TOKEN_EXPIRE_MINUTES` | `30` |
| `GEMINI_API_KEY` | API key Gemini của tài khoản, chỉ nhập trong Railway |
| `GEMINI_MODEL` | Model tài khoản có quyền truy cập; tham khảo `.env.java.example` |
| `PORT` | `8080` (khớp cổng Tomcat trong image) |
| `SOCKET_HOST` | `0.0.0.0` |
| `SOCKET_PORT` | `8001` |
| `RUNTIME_DIRECTORY` | `/var/lib/ezam/runtime` |
| `CORS_ALLOWED_ORIGINS` | Origin HTTPS của Frontend, không thêm dấu `/` cuối |

5. Gắn Volume **vào service Backend**, Mount Path `/var/lib/ezam/runtime`.
   Đặt một replica; runtime hiện có một tiến trình sở hữu, chưa hỗ trợ scale ngang.
6. Deploy. Chờ Tomcat khởi động và log `EZam Socket server started.`.
   Không cần tạo domain công khai cho Backend trong mô hình này.

`GET /api/health` kiểm tra HTTP; không thay thế kiểm tra đăng nhập/database/Socket.
Nếu chưa có domain Frontend để đặt CORS, tạo service Frontend và domain theo bước 3,
sau đó quay lại cập nhật biến này và redeploy Backend.

### 3. Tạo service `frontend`

1. Thêm service từ cùng GitHub repo, chọn nhánh `Ezam_Servlet`, tên `frontend`.
2. Root Directory `/frontend`; dùng Dockerfile có sẵn, để trống các lệnh build/start
   tùy chỉnh. Dockerfile build React rồi phục vụ bằng Caddy, không chạy Vite dev server.
3. Trong Settings của **service Frontend**, vào Networking → Public Networking →
   Generate Domain. Đặt Target Port `8080`. Đây là URL dùng để chia sẻ ứng dụng.
4. Giả sử domain được cấp là `https://YOUR-FRONTEND.up.railway.app`, đặt Variables:

| Biến Frontend | Giá trị |
| --- | --- |
| `PORT` | `8080` |
| `VITE_API_BASE_URL` | `https://YOUR-FRONTEND.up.railway.app` |
| `VITE_SOCKET_URL` | `https://YOUR-FRONTEND.up.railway.app` |
| `BACKEND_HTTP_UPSTREAM` | `${{backend.RAILWAY_PRIVATE_DOMAIN}}:8080` |
| `BACKEND_SOCKET_UPSTREAM` | `${{backend.RAILWAY_PRIVATE_DOMAIN}}:8001` |

Hai biến `VITE_*` dùng **cùng URL Frontend**, không thêm `/api` hoặc `/socket.io`.
Caddy giữ nguyên đường dẫn khi chuyển tiếp tới hai cổng Backend. Hai upstream là
`hostname:port`, không thêm dấu ngoặc nhọn thủ công ngoài cú pháp Reference Variable.
Không đặt API key/JWT secret/database password vào bất kỳ biến `VITE_*` nào.

5. Cập nhật `CORS_ALLOWED_ORIGINS` của Backend bằng origin Frontend này.
6. Redeploy Frontend sau khi đặt `VITE_*`: chúng được đưa vào bundle lúc build,
   đổi giá trị runtime đơn thuần không cập nhật bản React đã build.
7. Cả Frontend, Backend và database phải ở cùng project/environment để dùng mạng riêng.

### 4. Kiểm tra sau deploy

- Mở `https://YOUR-FRONTEND.up.railway.app/api/health`: nhận JSON trạng thái `ok`.
- Đăng ký/đăng nhập giáo viên; tạo preview AI, sửa và lưu một đề demo.
- Tạo phòng; mở một cửa sổ học sinh và vào bằng mã phòng.
- Bắt đầu thi; kiểm tra countdown, reconnect, nộp bài và bảng điểm cập nhật.
- F5 trang `/dashboard` hoặc trang làm bài để kiểm tra SPA fallback của Caddy.
- Với dữ liệu demo, kiểm tra runtime còn sau redeploy Backend có gắn Volume;
  tránh redeploy khi một buổi thi thật đang diễn ra.

Nếu Frontend báo 502, kiểm tra Backend deploy thành công, private domain và đúng hai
cổng `8080`/`8001`. Nếu trình duyệt gọi `localhost`, kiểm tra lại `VITE_*` và rebuild
Frontend. Nếu API trả 503, kiểm tra biến môi trường/schema database và log khởi tạo;
không gửi nội dung secret trong ảnh log hoặc chat.

### Tài liệu Railway tham khảo

- [Triển khai monorepo](https://docs.railway.com/deployments/monorepo)
- [Dockerfiles và biến lúc build](https://docs.railway.com/builds/dockerfiles)
- [Biến môi trường và Reference Variables](https://docs.railway.com/variables)
- [Public/private domain](https://docs.railway.com/networking/domains/working-with-domains)
- [Gắn Volume](https://docs.railway.com/volumes)
