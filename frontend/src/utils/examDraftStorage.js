const keyFor = (teacherId) => `quizAi.examDraft.${teacherId}`

export function readExamDraft(teacherId) {
  try {
    const value = JSON.parse(sessionStorage.getItem(keyFor(teacherId)))
    if (!value || typeof value.tenDeThi !== 'string' || !Array.isArray(value.cauHoiList) || !value.cauHoiList.length) return null
    if (typeof value.khoi !== 'string' || !['TEXT', 'FILE'].includes(value.kieuTaiLieu)) return null
    if (value.noiDungTaiLieu !== null && typeof value.noiDungTaiLieu !== 'string') return null
    if (!value.cauHoiList.every((question) => ['noiDung', 'dapAnA', 'dapAnB', 'dapAnC', 'dapAnD', 'dapAnDung'].every((field) => typeof question[field] === 'string'))) return null
    return value
  } catch { return null }
}

export function writeExamDraft(teacherId, draft) {
  try { sessionStorage.setItem(keyFor(teacherId), JSON.stringify(draft)) } catch {
    // The current page keeps the draft in memory when tab storage is unavailable.
  }
}

export function clearExamDraft(teacherId) {
  try { sessionStorage.removeItem(keyFor(teacherId)) } catch { /* In-memory draft is cleared by the page. */ }
}
