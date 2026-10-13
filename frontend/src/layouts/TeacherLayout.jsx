import { DoorOpen, LayoutDashboard, Library, LogOut, Sparkles } from 'lucide-react'
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import Brand from '../components/Brand.jsx'
import { useAuth } from '../contexts/AuthContext.jsx'

export default function TeacherLayout() {
  const { teacher, logout } = useAuth()
  const location = useLocation()
  const navItems = [
    { to: '/dashboard', label: 'Tổng quan', icon: LayoutDashboard, end: true },
    { to: '/exams', label: 'Kho đề thi', icon: Library },
    { to: '/exams/create-ai', label: 'Tạo đề bằng AI', icon: Sparkles },
    { to: '/rooms/create', label: 'Tạo phòng thi', icon: DoorOpen },
  ]
  const initials = teacher.fullName.trim().split(/\s+/).slice(-2).map((word) => Array.from(word)[0]).join('').toUpperCase()
  return (
    <div className="min-h-screen bg-slate-50">
      <a href="#main-content" className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:bg-white focus:p-4">Chuyển đến nội dung chính</a>
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4 px-5 py-4 sm:px-8">
          <Link to="/dashboard" aria-label="Quiz AI — Tổng quan"><Brand /></Link>
          <div className="flex items-center gap-3 sm:gap-5">
            <span className="hidden h-10 w-10 items-center justify-center rounded-full bg-sky-100 text-sm font-semibold text-brand-700 sm:flex" aria-hidden="true">{initials}</span>
            <div><p className="max-w-32 truncate text-sm font-semibold sm:max-w-56">{teacher.fullName}</p><p className="text-xs text-slate-500">Giáo viên</p></div>
            <button type="button" onClick={logout} className="button-secondary"><LogOut size={16} aria-hidden="true" /> Đăng xuất</button>
          </div>
        </div>
      </header>
      <div className="mx-auto grid max-w-7xl gap-6 px-5 py-6 sm:px-8 lg:grid-cols-[220px_1fr] lg:gap-10 lg:py-10">
        <nav aria-label="Điều hướng giáo viên">
          <p className="mb-3 hidden px-3 text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-400 lg:block">Không gian giáo viên</p>
          <div className="flex flex-wrap gap-2 lg:flex-col">
            {navItems.map(({ to, label, icon: Icon, end }) => (
              <NavLink key={to} to={to} end={end} className={({ isActive }) =>
                `flex items-center gap-3 rounded-xl px-4 py-3 text-sm font-semibold ${(isActive && !(to === '/exams' && location.pathname === '/exams/create-ai')) || (to === '/rooms/create' && location.pathname.startsWith('/rooms/')) ? 'bg-sky-100/70 text-brand-700' : 'text-slate-500 hover:bg-white hover:text-slate-800'}`
              }><Icon size={18} aria-hidden="true" /> {label}</NavLink>
            ))}
          </div>
        </nav>
        <main id="main-content" className="min-w-0"><Outlet /></main>
      </div>
    </div>
  )
}
