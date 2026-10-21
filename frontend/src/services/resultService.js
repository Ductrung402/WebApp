import apiClient from './apiClient.js'
import { parseSubmissionResult } from '../utils/results.js'

export async function getStudentResult(session, signal) {
  const { data } = await apiClient.get('/api/submissions/result', { params: { idHs: session.idHs, idPhongThi: session.idPhongThi }, signal, skipAuth: true })
  const result = parseSubmissionResult(data, session.idHs)
  if (!result) throw new Error('Kết quả nhận được chưa hợp lệ. Vui lòng thử lại.')
  return result
}

export function resultError(error) {
  if (error.response?.status === 409) return 'Bài thi chưa có kết quả. Vui lòng thử tải lại sau.'
  if (error.response?.status === 404) return 'Không tìm thấy kết quả cho phiên học sinh này.'
  return error.message === 'Kết quả nhận được chưa hợp lệ. Vui lòng thử lại.' ? error.message : 'Chưa tải được kết quả. Vui lòng kiểm tra kết nối và thử lại.'
}
