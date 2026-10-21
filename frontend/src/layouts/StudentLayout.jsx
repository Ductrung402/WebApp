import { ArrowUpRight } from 'lucide-react'
import { Link, Outlet } from 'react-router-dom'
import Brand from '../components/Brand.jsx'
import { StudentSessionProvider } from '../contexts/StudentSessionContext.jsx'

export default function StudentLayout() {
  return (
    <StudentSessionProvider>
      <div className="flex min-h-screen flex-col bg-slate-50">
        <a href="#student-content" className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:bg-white focus:p-4">Chuyển đến nội dung chính</a>
        <header className="border-b border-slate-200 bg-white">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-5 py-5 sm:px-8">
            <Link to="/join" aria-label="EZam — Vào phòng thi"><Brand /></Link>
            <Link to="/login" className="inline-flex items-center gap-1 text-sm font-medium text-slate-600 hover:text-brand-700">Dành cho giáo viên <ArrowUpRight size={16} aria-hidden="true" /></Link>
          </div>
        </header>
        <main id="student-content" className="mx-auto flex w-full max-w-6xl flex-1 flex-col justify-center px-5 py-10 sm:px-8 sm:py-14"><Outlet /></main>
        <footer className="px-5 py-6 text-center text-xs text-slate-600">EZam · Không gian học sinh</footer>
      </div>
    </StudentSessionProvider>
  )
}
