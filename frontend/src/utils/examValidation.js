export const ANSWER_KEYS = ['A', 'B', 'C', 'D']
export const QUESTION_FIELDS = ['noiDung', 'dapAnA', 'dapAnB', 'dapAnC', 'dapAnD', 'dapAnDung']

export function validateAiInput(values) {
  const errors = {}
  if (!values.tenDeThi.trim()) errors.tenDeThi = 'Vui lòng nhập tên đề thi.'
  const count = Number(values.numQuestions)
  if (!Number.isInteger(count) || count < 1 || count > 50) errors.numQuestions = 'Số câu hỏi phải là số nguyên từ 1 đến 50.'
  if (!values.rawText.trim()) errors.rawText = 'Vui lòng nhập tài liệu hoặc chọn một tệp văn bản.'
  return errors
}

export function validateExamDraft(draft) {
  const errors = {}
  if (!draft.tenDeThi.trim()) errors.tenDeThi = 'Vui lòng nhập tên đề thi.'
  if (!draft.cauHoiList.length) errors.cauHoiList = 'Đề thi cần ít nhất một câu hỏi.'
  errors.questions = draft.cauHoiList.map((question) => {
    const fields = {}
    for (const key of QUESTION_FIELDS.slice(0, 5)) {
      if (!question[key]?.trim()) fields[key] = 'Nội dung không được để trống.'
    }
    const options = ANSWER_KEYS.map((key) => question[`dapAn${key}`]?.trim().toLocaleLowerCase())
    if (new Set(options).size !== 4) fields.options = 'Bốn phương án phải khác nhau.'
    if (!ANSWER_KEYS.includes(question.dapAnDung)) fields.dapAnDung = 'Hãy chọn một đáp án đúng.'
    return fields
  })
  return errors
}

export function hasDraftErrors(errors) {
  return Boolean(errors.tenDeThi || errors.cauHoiList || errors.questions?.some((question) => Object.keys(question).length))
}

export function getExamError(error, action = 'load') {
  if (error.response?.status === 404) return 'Không tìm thấy đề thi hoặc bạn không có quyền truy cập.'
  if (error.response?.status === 409) return 'Đề thi đang được sử dụng nên không thể sửa hoặc xóa.'
  if (error.response?.status === 422) return 'Thông tin chưa hợp lệ. Hãy kiểm tra tên đề, số câu và các phương án trả lời.'
  if (action === 'generate' && /timeout|15-second/i.test(error.response?.data?.detail || '')) {
    return 'AI chưa trả kết quả trong 15 giây. Hãy thử lại với ít câu hỏi hoặc tài liệu ngắn hơn.'
  }
  if (action === 'generate' && error.response?.status === 400) return 'AI trả về câu hỏi chưa hợp lệ. Hãy thử sinh lại.'
  if (action === 'generate' && error.response?.status === 503) return 'Dịch vụ AI tạm thời chưa sẵn sàng. Vui lòng thử lại sau.'
  if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') return 'Kết nối đã quá thời gian chờ. Vui lòng thử lại.'
  if (error.response?.status >= 500) return 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.'
  if (error.isAxiosError) return 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra kết nối và thử lại.'
  return error.message || 'Đã có lỗi xảy ra. Vui lòng thử lại.'
}

export function formatExamDate(value) {
  if (!value) return 'Chưa có ngày'
  const date = new Date(`${value}T00:00:00`)
  return Number.isNaN(date.getTime()) ? 'Chưa có ngày' : new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium' }).format(date)
}
