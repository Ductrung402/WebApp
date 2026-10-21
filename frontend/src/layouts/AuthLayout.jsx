import { BookOpen, Check, GraduationCap, Sparkles } from 'lucide-react'
import Brand from '../components/Brand.jsx'

export default function AuthLayout({ children }) {
  return (
    <div className="grid min-h-screen lg:grid-cols-2">
      <aside className="relative hidden flex-col justify-between overflow-hidden border-r border-brand-100 bg-brand-50 p-12 lg:flex xl:p-16">
        <Brand />
        <div className="relative z-10 mx-auto my-10 w-full max-w-lg">
          <p className="mb-5 flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.18em] text-brand-700"><GraduationCap size={18} aria-hidden="true" /> Không gian giáo viên EZam</p>
          <h2 className="text-4xl font-bold leading-tight tracking-tight text-slate-900 xl:text-5xl">Soạn đề thông minh.<br /><span className="text-brand-700">Dạy học chủ động.</span></h2>
          <p className="mt-6 max-w-sm text-base leading-7 text-slate-600">EZam giúp bạn tạo đề bằng AI và tổ chức những buổi thi trực tuyến hiệu quả.</p>
          <div aria-hidden="true" className="relative mt-12 max-w-sm rounded-2xl border border-white bg-white p-6 shadow-[0_20px_60px_-20px_rgba(27,82,50,0.25)]">
            <div className="flex items-center gap-3 border-b border-slate-100 pb-5">
              <span className="rounded-xl bg-brand-100 p-3 text-brand-700"><BookOpen size={22} /></span>
              <div><p className="text-sm font-semibold">Mỗi câu hỏi, một khám phá</p><p className="mt-1 text-xs text-slate-600">Học hỏi bắt đầu từ sự tò mò</p></div>
            </div>
            <div className="mt-5 space-y-3">
              <div className="h-2 w-4/5 rounded bg-slate-100" /><div className="h-2 w-3/5 rounded bg-slate-100" />
              <div className="mt-5 flex items-center gap-3 rounded-lg border border-brand-200 bg-brand-50 p-3 text-brand-700">
                <span className="flex h-5 w-5 items-center justify-center rounded-full bg-brand-700 text-white"><Check size={12} /></span>
                <span className="text-xs font-medium">Khơi mở kiến thức mới</span>
              </div>
            </div>
            <span className="absolute -right-4 -top-4 rounded-xl bg-brand-700 p-3 text-white shadow-lg"><Sparkles size={22} /></span>
          </div>
        </div>
        <p className="text-xs text-slate-600">EZam · Đồng hành cùng việc dạy và học</p>
        <div aria-hidden="true" className="pointer-events-none absolute -bottom-32 -right-32 h-96 w-96 rounded-full border-[48px] border-brand-100/60" />
      </aside>
      <main className="flex flex-col items-center justify-center bg-white px-6 py-10 sm:px-10">
        <div className="mb-10 lg:hidden"><Brand /></div><div className="w-full max-w-md">{children}</div>
        <p className="mt-10 text-center text-xs text-slate-600">Không gian giáo viên · EZam</p>
      </main>
    </div>
  )
}
