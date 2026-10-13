import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext.jsx'
import { getAccessToken } from '../utils/tokenStorage.js'
import SessionStatus from './SessionStatus.jsx'

export default function PublicRoute() {
  const { teacher, status } = useAuth()
  if (status === 'checking' || status === 'error') return <SessionStatus />
  if (teacher && getAccessToken()) return <Navigate to="/dashboard" replace />
  return <Outlet />
}
