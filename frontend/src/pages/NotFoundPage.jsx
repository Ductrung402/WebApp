import { Link } from 'react-router-dom'
import Brand from '../components/Brand.jsx'

export default function NotFoundPage() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 px-6">
      <div className="max-w-md text-center">
        <Brand /><p className="mt-10 text-sm font-semibold text-brand-700">404</p>
        <h1 className="mt-3 text-3xl font-bold">Không tìm thấy trang</h1>
        <p className="mt-4 text-sm text-slate-500">Đường dẫn này không tồn tại. Hãy quay về không gian của bạn.</p>
        <Link to="/dashboard" className="button-primary mt-8">Về trang chính</Link>
      </div>
    </main>
  )
}
