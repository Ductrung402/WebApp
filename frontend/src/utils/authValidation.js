export function validateAuthForm(values, isRegister = false) {
  const errors = {}
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(values.email.trim())) {
    errors.email = 'Vui lòng nhập địa chỉ email hợp lệ.'
  }
  if (Array.from(values.password).length < 8) {
    errors.password = 'Mật khẩu cần ít nhất 8 ký tự.'
  } else if (new TextEncoder().encode(values.password).length > 72 || values.password.includes('\0')) {
    errors.password = 'Mật khẩu tối đa 72 byte và không chứa ký tự null.'
  }
  if (isRegister && !values.fullName.trim()) errors.fullName = 'Vui lòng nhập họ và tên.'
  return errors
}

export function getAuthError(error) {
  if (error.response?.status === 401) return 'Email hoặc mật khẩu không chính xác.'
  if (error.response?.status === 409) return 'Email này đã được đăng ký. Vui lòng đăng nhập hoặc dùng email khác.'
  if (error.response?.status === 422) return 'Thông tin chưa hợp lệ. Vui lòng kiểm tra lại các trường trong biểu mẫu.'
  if (error.response?.status >= 500) return 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.'
  if (error.isAxiosError) return 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra kết nối và thử lại.'
  return error.message || 'Đã có lỗi xảy ra. Vui lòng thử lại.'
}
