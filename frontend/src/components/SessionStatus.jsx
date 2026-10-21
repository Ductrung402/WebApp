import { LoaderCircle, RefreshCw } from 'lucide-react'
import { useAuth } from '../contexts/AuthContext.jsx'
import Brand from './Brand.jsx'

export default function SessionStatus() {
  const { status, sessionError, refreshSession, logout } = useAuth()
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 p-6">
      <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 text-center shadow-sm">
        <Brand />
        {status === 'checking' ? (
          <p role="status" className="mt-8 flex items-center justify-center gap-3 text-sm text-slate-600">
            <LoaderCircle className="animate-spin text-brand-700" size={20} aria-hidden="true" /> Đang xác minh phiên đăng nhập…
          </p>
        ) : (
          <>
            <h1 className="mt-8 text-xl font-semibold">Chưa thể xác minh phiên đăng nhập</h1>
            <p role="alert" className="mt-3 text-sm leading-6 text-slate-600">{sessionError}</p>
            <button type="button" onClick={refreshSession} className="button-primary mt-6 w-full"><RefreshCw size={16} aria-hidden="true" /> Thử lại</button>
            <button type="button" onClick={logout} className="mt-5 text-sm font-medium text-brand-700 underline underline-offset-4">Đăng nhập tài khoản khác</button>
          </>
        )}
      </div>
    </main>
  )
}
