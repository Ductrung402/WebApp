export const TOKEN_KEY = 'quizAi.teacherAccessToken'
export const AUTH_EXPIRED_EVENT = 'quizAi:authExpired'

export function getAccessToken() {
  try { return window.localStorage.getItem(TOKEN_KEY) } catch { return null }
}

export function saveAccessToken(token) {
  try { window.localStorage.setItem(TOKEN_KEY, token) } catch {
    throw new Error('Trình duyệt không cho phép lưu phiên đăng nhập. Vui lòng kiểm tra cài đặt lưu trữ.')
  }
}

export function removeAccessToken() {
  try { window.localStorage.removeItem(TOKEN_KEY) } catch {
    // Auth Context also clears its in-memory state when storage is unavailable.
  }
}
