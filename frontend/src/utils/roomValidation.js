export const ROOM_STATUS = {
  DANG_CHO: { label: 'Đang chờ học sinh', className: 'bg-amber-50 text-amber-800 ring-amber-200' },
  DANG_THI: { label: 'ĐANG THI', className: 'bg-sky-50 text-brand-700 ring-sky-200' },
  DA_DONG: { label: 'Đã đóng', className: 'bg-slate-100 text-slate-600 ring-slate-200' },
}

export function validateRoomInput(values, exams) {
  const errors = {}
  const selected = exams.find((exam) => exam.idDeThi === values.idDeThi)
  if (!selected || !(selected.soLuongCauHoi > 0)) errors.idDeThi = 'Vui lòng chọn một đề thi có câu hỏi trong kho của bạn.'
  if (!values.tenLop.trim()) errors.tenLop = 'Vui lòng nhập tên lớp hoặc nhóm.'
  const minutes = Number(values.thoiGianLamBai)
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > 2147483647) {
    errors.thoiGianLamBai = 'Thời gian phải là số nguyên phút từ 1 đến 2147483647.'
  }
  return errors
}

export function getRoomError(error, action = 'load') {
  if (error.response?.status === 401) return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.'
  if (error.response?.status === 403) return 'Bạn không có quyền truy cập phòng thi này.'
  if (error.response?.status === 404) return action === 'create'
    ? 'Đề thi không còn tồn tại hoặc bạn không có quyền sử dụng. Hãy tải lại kho đề.'
    : 'Không tìm thấy phòng thi hoặc bạn không có quyền truy cập.'
  if (error.response?.status === 409) return 'Không thể tạo phòng với dữ liệu hiện tại. Vui lòng kiểm tra và thử lại.'
  if (error.response?.status === 422) return 'Thông tin chưa hợp lệ. Hãy kiểm tra đề thi, tên lớp và thời gian làm bài.'
  if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') return 'Kết nối đã quá thời gian chờ. Vui lòng thử lại.'
  if (error.response?.status >= 500) return 'Máy chủ tạm thời chưa sẵn sàng. Vui lòng thử lại.'
  if (error.isAxiosError) return 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra kết nối.'
  return 'Chưa thể tải thông tin. Vui lòng thử lại.'
}
