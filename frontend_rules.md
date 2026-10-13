# FRONTEND DEVELOPMENT RULES

## 1. Tech Stack
- Framework: ReactJS (Functional Components, Hooks)
- Styling: TailwindCSS
- Realtime: socket.io-client
- Routing: react-router-dom

## 2. Component Architecture
- Tách biệt UI Components (chỉ hiển thị) và Page Components (chứa logic gọi API).
- Không tự ý sinh thêm các thư viện UI Kit nặng nề nếu TailwindCSS có thể xử lý được.

## 3. State Management & Realtime
- Xử lý trạng thái phòng thi (Đang chờ, Đang thi) qua WebSockets, không dùng HTTP Polling.
- Luôn kiểm tra Token trong LocalStorage/SessionStorage trước khi điều hướng sang trang quản trị của Giáo viên.