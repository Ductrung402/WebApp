export const STUDENT_SESSION_KEY = 'quizAi.studentSession'
const sessionFields = ['idHs', 'maCodePhongThi', 'hoVaTen', 'idPhongThi', 'tenLop', 'thoiGianLamBai']

export function validateStudentSession(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  if (!sessionFields.slice(0, 5).every((key) => typeof value[key] === 'string' && value[key].trim())) return null
  if (!/^[A-Z0-9]{6}$/.test(value.maCodePhongThi)) return null
  if (!Number.isInteger(value.thoiGianLamBai) || value.thoiGianLamBai < 1 || value.thoiGianLamBai > 2147483647) return null
  // Store only the anonymous join DTO; never copy tokens or unrelated response data.
  return Object.fromEntries(sessionFields.map((key) => [key, value[key]]))
}

export function readStudentSession() {
  try {
    const raw = sessionStorage.getItem(STUDENT_SESSION_KEY)
    if (raw === null) return null
    const session = validateStudentSession(JSON.parse(raw))
    if (!session) sessionStorage.removeItem(STUDENT_SESSION_KEY)
    return session
  } catch {
    try { sessionStorage.removeItem(STUDENT_SESSION_KEY) } catch { /* Storage may be blocked. */ }
    return null
  }
}

export function writeStudentSession(session) {
  const validated = validateStudentSession(session)
  if (!validated) throw new Error('Invalid student session.')
  sessionStorage.setItem(STUDENT_SESSION_KEY, JSON.stringify(validated))
  return validated
}
