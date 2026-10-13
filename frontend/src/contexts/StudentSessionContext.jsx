import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import { STUDENT_SESSION_KEY, readStudentSession, validateStudentSession, writeStudentSession } from '../utils/studentSessionStorage.js'

const StudentSessionContext = createContext(null)

export function StudentSessionProvider({ children }) {
  const [session, setSession] = useState(readStudentSession)
  const [storageError, setStorageError] = useState('')

  const rememberSession = useCallback((joined) => {
    const validated = validateStudentSession(joined)
    if (!validated) throw new Error('Invalid anonymous join response.')
    setSession(validated)
    try {
      writeStudentSession(validated)
      setStorageError('')
    } catch {
      // Join has already succeeded. Keep it in memory rather than submit again.
      setStorageError('Bạn đã vào phòng, nhưng trình duyệt không lưu được phiên. Hãy giữ trang này mở; tải lại trang có thể làm mất thông tin tham gia.')
    }
    return validated
  }, [])

  const clearSession = useCallback(() => {
    setSession(null)
    setStorageError('')
    try { sessionStorage.removeItem(STUDENT_SESSION_KEY) } catch { /* Keep navigation available when storage is blocked. */ }
  }, [])

  const value = useMemo(() => ({ session, storageError, rememberSession, clearSession }), [session, storageError, rememberSession, clearSession])
  return <StudentSessionContext.Provider value={value}>{children}</StudentSessionContext.Provider>
}

export function useStudentSession() {
  const context = useContext(StudentSessionContext)
  if (!context) throw new Error('useStudentSession must be used within StudentSessionProvider.')
  return context
}
