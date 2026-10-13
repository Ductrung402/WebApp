import { GraduationCap, Mail, MapPin, School, UserRound } from 'lucide-react'
import { useAuth } from '../contexts/AuthContext.jsx'

export default function DashboardPage() {
  const { teacher } = useAuth()
  const fields = [
    { label: 'Họ và tên', value: teacher.fullName, icon: UserRound },
    { label: 'Email', value: teacher.email, icon: Mail },
    { label: 'Trường', value: teacher.school || 'Chưa cung cấp', icon: School },
    { label: 'Tỉnh / Thành phố', value: teacher.province || 'Chưa cung cấp', icon: MapPin },
  ]
  return (
    <>
      <p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">Tổng quan</p>
      <h1 className="mt-3 text-3xl font-bold tracking-tight text-slate-900">Xin chào, {teacher.fullName}!</h1>
      <p className="mt-3 text-sm leading-6 text-slate-500">Chào mừng bạn đến với không gian giáo viên của Quiz AI.</p>
      <section className="mt-8 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm" aria-labelledby="profile-title">
        <div className="flex items-center gap-4 border-b border-slate-100 px-6 py-6 sm:px-8">
          <span className="rounded-xl bg-sky-50 p-3 text-brand-700"><GraduationCap size={24} aria-hidden="true" /></span>
          <div><h2 id="profile-title" className="text-lg font-semibold">Thông tin tài khoản</h2><p className="mt-1 text-sm text-slate-500">Hồ sơ giáo viên của bạn</p></div>
        </div>
        <dl className="grid gap-7 p-6 sm:grid-cols-2 sm:p-8">
          {fields.map(({ label, value, icon: Icon }) => (
            <div key={label} className="min-w-0">
              <dt className="flex items-center gap-2 text-xs font-medium text-slate-500"><Icon size={15} aria-hidden="true" /> {label}</dt>
              <dd className="mt-3 break-words text-sm font-medium text-slate-800">{value}</dd>
            </div>
          ))}
        </dl>
      </section>
    </>
  )
}
