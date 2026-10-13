import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext.jsx'
import { getAccessToken } from '../utils/tokenStorage.js'
import SessionStatus from './SessionStatus.jsx'

export default function ProtectedRoute() {
  const { teacher, status } = useAuth()
  const location = useLocation()

  if (status === 'checking' || status === 'error') return <SessionStatus />
  if (!getAccessToken() || !teacher) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  return <Outlet />
}
