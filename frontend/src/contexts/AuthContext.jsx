import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { getCurrentTeacher, loginTeacher, registerTeacher } from '../services/authService.js'
import { getAuthError } from '../utils/authValidation.js'
import { AUTH_EXPIRED_EVENT, getAccessToken, removeAccessToken, saveAccessToken, TOKEN_KEY } from '../utils/tokenStorage.js'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [teacher, setTeacher] = useState(null)
  const [status, setStatus] = useState(() => getAccessToken() ? 'checking' : 'anonymous')
  const [sessionError, setSessionError] = useState('')
  const [sessionNotice, setSessionNotice] = useState('')
  const operationVersion = useRef(0)

  const logout = useCallback(() => {
    operationVersion.current += 1
    removeAccessToken()
    setTeacher(null)
    setStatus('anonymous')
    setSessionError('')
    setSessionNotice('')
  }, [])

  const refreshSession = useCallback(async () => {
    const version = ++operationVersion.current
    if (!getAccessToken()) {
      setTeacher(null)
      setStatus('anonymous')
      setSessionError('')
      return
    }
    setTeacher(null)
    setStatus('checking')
    setSessionError('')
    try {
      const currentTeacher = await getCurrentTeacher()
      if (version === operationVersion.current) {
        setTeacher(currentTeacher)
        setStatus('authenticated')
        setSessionNotice('')
      }
    } catch (error) {
      if (version === operationVersion.current) {
        if (error.response?.status === 401) {
          logout()
          setSessionNotice('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.')
        } else {
          setStatus('error')
          setSessionError(getAuthError(error))
        }
      }
    }
  }, [logout])

  useEffect(() => {
    function handleExpired() {
      logout()
      setSessionNotice('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.')
    }
    function handleStorage(event) {
      if (event.key === TOKEN_KEY || event.key === null) refreshSession()
    }
    window.addEventListener(AUTH_EXPIRED_EVENT, handleExpired)
    window.addEventListener('storage', handleStorage)
    refreshSession()
    return () => {
      operationVersion.current += 1
      window.removeEventListener(AUTH_EXPIRED_EVENT, handleExpired)
      window.removeEventListener('storage', handleStorage)
    }
  }, [logout, refreshSession])

  const login = useCallback(async (credentials) => {
    const version = ++operationVersion.current
    let savedToken = null
    try {
      const token = await loginTeacher(credentials)
      if (version !== operationVersion.current) throw new Error('Phiên đăng nhập đã thay đổi. Vui lòng thử lại.')
      if (!token.access_token || token.token_type !== 'bearer') throw new Error('Phản hồi đăng nhập không hợp lệ.')
      savedToken = token.access_token
      saveAccessToken(savedToken)
      const currentTeacher = await getCurrentTeacher()
      if (version !== operationVersion.current) throw new Error('Phiên đăng nhập đã thay đổi. Vui lòng thử lại.')
      setTeacher(currentTeacher)
      setStatus('authenticated')
      setSessionError('')
      setSessionNotice('')
      return currentTeacher
    } catch (error) {
      if (version === operationVersion.current) {
        if (savedToken && getAccessToken() === savedToken) removeAccessToken()
        setTeacher(null)
        setStatus('anonymous')
      }
      throw error
    }
  }, [])

  const register = useCallback((profile) => registerTeacher(profile), [])
  const updateTeacher = useCallback((newInfo) => {
    setTeacher((current) => current && current.idGv === newInfo.idGv ? newInfo : current)
  }, [])
  const value = useMemo(() => ({
    teacher, status, sessionError, sessionNotice, login, logout, register, refreshSession, updateTeacher,
  }), [teacher, status, sessionError, sessionNotice, login, logout, register, refreshSession, updateTeacher])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider.')
  return context
}
