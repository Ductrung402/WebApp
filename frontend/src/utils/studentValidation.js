export const normalizeRoomCode = (value) => value.trim().toUpperCase()

export function validateStudentInput(values) {
  const errors = {}
  if (!values.hoVaTen.trim()) errors.hoVaTen = 'Vui lòng nhập họ và tên.'
  if (!/^[A-Z0-9]{6}$/.test(normalizeRoomCode(values.maCodePhongThi))) {
    errors.maCodePhongThi = 'Mã phòng gồm đúng 6 ký tự chữ in hoa hoặc số.'
  }
  return errors
}

export function getStudentEntryError(error) {
  if (error.response?.status === 404) return 'Mã phòng không tồn tại. Hãy kiểm tra lại mã giáo viên cung cấp.'
  if (error.response?.status === 409) return 'Phòng thi đã đóng hoặc không thể nhận thêm thí sinh.'
  if (error.response?.status === 422) return 'Thông tin chưa hợp lệ. Hãy kiểm tra họ tên và mã phòng.'
  if (error.response?.status === 401 || error.response?.status === 403) return 'Chưa thể vào phòng thi. Vui lòng liên hệ giáo viên.'
  if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') return 'Kết nối đã quá thời gian chờ. Vui lòng kiểm tra kết nối trước khi thử lại.'
  if (error.response?.status >= 500) return 'Máy chủ tạm thời chưa sẵn sàng. Vui lòng thử lại sau.'
  if (error.isAxiosError) return 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra kết nối.'
  return 'Chưa nhận được thông tin phòng hợp lệ. Vui lòng liên hệ giáo viên.'
}
