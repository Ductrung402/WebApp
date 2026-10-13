# 🚀 HỆ THỐNG TẠO ĐỀ THI AI & TỔ CHỨC THI TRỰC TUYẾN
*(AI Quiz Generator & Real-time Exam Management System)*

## 1. Thông Tin Dự Án
- **Loại hình:** Đồ án môn học Lập trình Web.
- **Quy mô nhân sự:** 1 Developer thực hiện độc lập.
- **Thời gian triển khai dự kiến:** ~5 tuần.
- **Mục tiêu cốt lõi:** Tự động hóa quá trình sinh câu hỏi trắc nghiệm từ tài liệu thô bằng AI và cung cấp nền tảng thi thử trực tuyến không cần đăng ký tài khoản cho học sinh.

## 2. Tech Stack (Công Nghệ Sử Dụng)
- **Frontend:** ReactJS, TailwindCSS.
- **Backend:** FastAPI (Python), RESTful API.
- **Cơ sở dữ liệu:** PostgreSQL (ORM: SQLAlchemy).
- **Real-time Communication:** Socket.io (Đồng bộ trạng thái phòng thi và thời gian làm bài).
- **Trí tuệ nhân tạo:** Gemini API (Xử lý NLP, sinh JSON câu hỏi trắc nghiệm).

## 3. Các Tính Năng Cốt Lõi (Core Modules)
### 3.1. Module Giáo Viên (Teacher / Admin)
- Xác thực người dùng (Login/Register với JWT).
- **Tạo đề thi bằng AI:** Upload tài liệu (File/Text) -> Gọi Gemini API -> Trả về danh sách câu hỏi trắc nghiệm -> Kiểm duyệt & Lưu trữ.
- Quản lý kho đề thi cá nhân (CRUD).
- Tạo và quản lý Phòng thi (Sinh mã code tự động 6 ký tự).
- Giám sát phòng thi theo thời gian thực (Live tracking trạng thái học sinh).
- Xem bảng điểm tự động cập nhật.

### 3.2. Module Học Sinh (Student / Guest)
- Truy cập phòng thi ẩn danh (Chỉ cần nhập Họ tên + Mã code phòng thi).
- Làm bài thi trắc nghiệm với đồng hồ đếm ngược.
- Nộp bài và xem điểm số tự động.
- **Cơ chế chống rớt mạng:** Tự động khôi phục trạng thái bài làm nếu bị mất kết nối (Dựa trên session tạm thời tại phòng thi).

## 4. Kiến Trúc Cơ Sở Dữ Liệu (Lõi Kỹ Thuật)
Hệ thống tuân thủ nghiêm ngặt mô hình Sơ đồ Lớp (Class Diagram) với các thực thể và ràng buộc sau:

*   **GiaoVien (1) -- (0..*) DeThi:** Giáo viên tạo và quản lý kho đề.
*   **DeThi (1) ◆-- (1..*) CauHoi:** Quan hệ bao hàm. Đề thi chứa các câu hỏi trắc nghiệm (có 4 đáp án A, B, C, D và 1 đáp án đúng).
*   **GiaoVien (1) -- (0..*) PhongThi:** Giáo viên tổ chức phòng thi.
*   **PhongThi (0..*) ◇-- (1) DeThi:** Phòng thi sử dụng đề thi có sẵn.
*   **PhongThi (1) ◆-- (0..*) HocSinh:** Phòng thi quản lý danh sách thí sinh vãng lai.
*   **PhongThi (1) ◆-- (0..*) KetQuaThi:** Phòng thi lưu trữ bảng điểm.
*   **HocSinh (1) -- (1) KetQuaThi:** Mỗi học sinh gắn với một bảng điểm trong 1 phiên thi.

**Các Enum Trạng Thái Quan Trọng:**
- `TrangThaiPhong`: `DANG_CHO` (Đợi học sinh vào) -> `DANG_THI` (Đang làm bài) -> `DA_DONG` (Kết thúc).
- `TrangThaiBaiLam`: `DANG_THI` (Đang chọn đáp án) -> `MAT_KET_NOI` (Rớt mạng/Thoát trang) -> `HOAN_THANH` (Đã nộp bài).

## 5. Quy Tắc Phát Triển (For AI Assistants & LLMs)
1. **Tuyệt đối không** tự ý thay đổi cấu trúc Database hoặc thêm các trường dữ liệu ngoài Class Diagram đã chốt.
2. Mọi logic xử lý thời gian thực bắt buộc phải đi qua event của `Socket.io`.
3. Tách biệt rõ ràng logic nghiệp vụ AI (gọi Gemini API) ra một service riêng (vd: `gemini_service.py`) để dễ bảo trì.
4. Đảm bảo tính toàn vẹn dữ liệu (Referential Integrity) trên PostgreSQL khi thực hiện các lệnh Xóa (Delete) đối với các quan hệ bao hàm (Composition).