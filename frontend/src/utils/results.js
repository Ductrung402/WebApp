export function parseSubmissionResult(value, studentId = null) {
  if (!value || typeof value.idHs !== 'string' || !value.idHs || (studentId && value.idHs !== studentId) || value.trangThai !== 'HOAN_THANH') return null
  if (typeof value.diemSo !== 'number' || !Number.isFinite(value.diemSo) || value.diemSo < 0 || value.diemSo > 10) return null
  if (!Number.isSafeInteger(value.soCauDung) || !Number.isSafeInteger(value.tongSoCau) || value.soCauDung < 0 || value.tongSoCau < 1 || value.soCauDung > value.tongSoCau) return null
  return Object.fromEntries(['idHs', 'diemSo', 'soCauDung', 'tongSoCau', 'trangThai'].map((key) => [key, value[key]]))
}

export const validScore = (value) => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 10
export const formatScore = (value) => validScore(value) ? value.toFixed(2).replace(/\.?0+$/, '') : '—'

export function parseScoreRow(value, code) {
  if (!value || typeof value.idHs !== 'string' || !value.idHs || typeof value.hoVaTen !== 'string' || value.maCodePhongThi !== code || !['DANG_THI', 'MAT_KET_NOI', 'HOAN_THANH'].includes(value.trangThai)) return null
  if (value.trangThai === 'HOAN_THANH' && !validScore(value.diemSo)) return null
  return { idHs: value.idHs, hoVaTen: value.hoVaTen, maCodePhongThi: code, trangThai: value.trangThai, diemSo: value.trangThai === 'HOAN_THANH' ? value.diemSo : null }
}

export function rankStudents(students) {
  const completed = (student) => student.trangThai === 'HOAN_THANH' && validScore(student.diemSo)
  const rows = [...students].sort((a, b) => Number(completed(b)) - Number(completed(a)) || (completed(a) && completed(b) ? b.diemSo - a.diemSo : 0) || a.hoVaTen.localeCompare(b.hoVaTen, 'vi') || a.idHs.localeCompare(b.idHs))
  let rank = 0
  return rows.map((student, index) => {
    if (completed(student)) rank = index && student.diemSo === rows[index - 1].diemSo ? rank : index + 1
    return { ...student, rank: completed(student) ? rank : null }
  })
}

export function roomStatistics(students) {
  const submitted = students.filter((student) => student.trangThai === 'HOAN_THANH')
  const graded = submitted.filter((student) => validScore(student.diemSo))
  return { total: students.length, submitted: submitted.length, average: graded.length && graded.length === submitted.length ? graded.reduce((sum, student) => sum + student.diemSo, 0) / graded.length : null }
}

export const RESULT_MESSAGES = [
  { minimum: 8.5, title: 'Kết quả rất tốt!', text: 'Bạn đã nắm vững phần lớn kiến thức. Hãy tiếp tục phát huy nhé.' },
  { minimum: 6.5, title: 'Bạn đã làm tốt!', text: 'Bạn đã có nền tảng tốt. Ôn lại một chút sẽ giúp bạn tự tin hơn ở lần tới.' },
  { minimum: 5, title: 'Bạn đang tiến bộ!', text: 'Hãy dành thêm thời gian củng cố những phần còn chưa chắc nhé.' },
  { minimum: 0, title: 'Mỗi lần làm bài là một cơ hội học hỏi.', text: 'Bạn đã hoàn thành bài thi. Hãy ôn lại kiến thức và thử sức tiếp nhé.' },
]

export function resultsCsv(students, closed = false) {
  const labels = { HOAN_THANH: 'ĐÃ HOÀN THÀNH', MAT_KET_NOI: 'MẤT KẾT NỐI', DANG_THI: 'ĐANG LÀM BÀI' }
  const cell = (value) => {
    let text = String(value ?? '')
    if (/^\s*[=+\-@]/.test(text)) text = "'" + text
    return '"' + text.replace(/"/g, '""') + '"'
  }
  const rows = [['Hạng', 'Họ và tên', 'Trạng thái', 'Điểm / 10'], ...rankStudents(students).map((student) => [student.rank ?? '', student.hoVaTen, closed && student.trangThai !== 'HOAN_THANH' ? 'CHƯA HOÀN THÀNH' : labels[student.trangThai] || 'Đã tham gia', student.trangThai === 'HOAN_THANH' ? formatScore(student.diemSo) : ''])]
  return '\ufeff' + rows.map((row) => row.map(cell).join(',')).join('\r\n')
}
