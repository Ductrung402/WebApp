import { CheckCircle2, UsersRound, WifiOff } from 'lucide-react'

export default function StudentRoster({ students, updatedAt, stale, closed = false }) {
  return (
    <section aria-labelledby="student-roster-title" className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 p-5 sm:px-6">
        <h2 id="student-roster-title" className="flex items-center gap-2 text-lg font-semibold"><UsersRound size={20} className="text-brand-700" aria-hidden="true" /> Thí sinh đã tham gia</h2>
        <span aria-live="polite" aria-atomic="true" className="rounded-full bg-sky-50 px-3 py-1 text-xs font-semibold text-brand-700">{students.length} thí sinh</span>
      </div>
      {!students.length ? (
        <div className="px-6 py-12 text-center"><UsersRound size={36} className="mx-auto text-slate-300" aria-hidden="true" /><p className="mt-4 font-medium text-slate-700">Chưa có học sinh tham gia</p><p className="mt-2 text-sm leading-6 text-slate-500">{closed ? 'Phòng đã đóng và không nhận thêm học sinh.' : 'Danh sách sẽ tự động cập nhật khi học sinh nhập mã phòng.'}</p></div>
      ) : (
        <ol className="divide-y divide-slate-100">
          {students.map((student, index) => (
            <li key={student.idHs} data-testid={`student-${student.idHs}`} className={`flex flex-wrap items-center gap-3 px-5 py-4 sm:px-6 ${student.trangThai === 'MAT_KET_NOI' ? 'bg-amber-50' : ''}`}>
              <span className="w-5 shrink-0 text-xs tabular-nums text-slate-400" aria-hidden="true">{index + 1}</span>
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-sky-50 text-xs font-semibold text-brand-700" aria-hidden="true">{student.hoVaTen.trim().split(/\s+/).slice(-2).map((word) => Array.from(word)[0]).join('').toUpperCase()}</span>
              <p className="min-w-0 flex-1 break-words text-sm font-medium text-slate-800">{student.hoVaTen}</p>
              {student.trangThai === 'MAT_KET_NOI' ? <span className="inline-flex items-center gap-2 rounded-full bg-amber-100 px-3 py-1 text-xs font-semibold text-amber-800"><WifiOff size={15} aria-hidden="true" />MẤT KẾT NỐI</span> : <CheckCircle2 size={18} className="shrink-0 text-emerald-600" aria-label={student.trangThai === 'HOAN_THANH' ? 'Đã hoàn thành' : 'Đã tham gia'} />}
            </li>
          ))}
        </ol>
      )}
      <div className="border-t border-slate-100 px-5 py-3 text-xs leading-5 text-slate-500 sm:px-6">
        {stale ? 'Đang hiển thị danh sách ở lần cập nhật gần nhất.' : closed ? 'Phòng đã đóng. Danh sách không nhận thêm thí sinh.' : updatedAt ? `Cập nhật lúc ${updatedAt.toLocaleTimeString('vi-VN')}. Danh sách tự động cập nhật.` : 'Danh sách tự động cập nhật.'}
      </div>
    </section>
  )
}
