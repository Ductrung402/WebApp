import { CheckCircle2, Download, Trophy, UsersRound, WifiOff } from 'lucide-react'
import { formatScore, rankStudents, resultsCsv, roomStatistics } from '../../utils/results.js'

export default function TeacherLiveLeaderboard({ students, code, stale = false, closed = false }) {
  const rows = rankStudents(students)
  const stats = roomStatistics(students)
  function exportResults() {
    const url = URL.createObjectURL(new Blob([resultsCsv(students, closed)], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `ket-qua-${code}.csv`
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
  }
  return (
    <section aria-labelledby="leaderboard-title" className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 p-5 sm:px-6"><h2 id="leaderboard-title" className="inline-flex items-center gap-2 text-lg font-semibold"><Trophy size={20} className="text-brand-700" aria-hidden="true" />Bảng điểm trực tiếp</h2><button type="button" onClick={exportResults} disabled={!students.length} className="button-secondary !px-3 !py-2 !text-xs"><Download size={15} aria-hidden="true" />Xuất CSV</button></div>
      <dl className="grid grid-cols-3 divide-x divide-slate-100 border-b border-slate-100 py-5 text-center"><div><dt className="text-xs text-slate-600">Tổng thí sinh</dt><dd data-testid="leaderboard-total" className="mt-2 text-2xl font-bold">{stats.total}</dd></div><div><dt className="text-xs text-slate-600">Đã nộp</dt><dd data-testid="leaderboard-submitted" className="mt-2 text-2xl font-bold text-emerald-700">{stats.submitted}</dd></div><div><dt className="text-xs text-slate-600">Điểm trung bình</dt><dd data-testid="leaderboard-average" className="mt-2 text-2xl font-bold text-brand-700">{formatScore(stats.average)}</dd></div></dl>
      {!rows.length ? <div className="p-10 text-center text-sm text-slate-600"><UsersRound className="mx-auto mb-4 text-slate-600" size={32} aria-hidden="true" />Chưa có thí sinh trong phòng.</div> : <ol className="divide-y divide-slate-100" aria-live="polite" aria-relevant="additions text">
        {rows.map((student) => {
          const completed = student.trangThai === 'HOAN_THANH'
          const offline = !closed && student.trangThai === 'MAT_KET_NOI'
          return <li key={student.idHs} data-testid={`student-${student.idHs}`} className={`grid grid-cols-[24px_minmax(0,1fr)_auto] items-center gap-3 px-5 py-5 sm:px-6 ${completed ? 'bg-emerald-50/60' : offline ? 'bg-amber-50' : ''}`}><span className="text-sm font-semibold tabular-nums text-slate-600" aria-label={student.rank ? `Hạng ${student.rank}` : 'Chưa xếp hạng'}>{student.rank ?? '—'}</span><div className="min-w-0"><p className="break-words text-sm font-semibold text-slate-800">{student.hoVaTen}</p><span className={`mt-2 inline-flex items-center gap-1.5 rounded-full px-2 py-1 text-[10px] font-semibold sm:text-xs ${completed ? 'bg-emerald-100 text-emerald-800' : offline ? 'bg-amber-100 text-amber-800' : closed ? 'bg-slate-100 text-slate-600' : 'bg-brand-50 text-brand-700'}`}>{completed ? <CheckCircle2 size={13} aria-hidden="true" /> : offline ? <WifiOff size={13} aria-hidden="true" /> : null}{completed ? 'ĐÃ HOÀN THÀNH' : closed ? 'CHƯA HOÀN THÀNH' : offline ? 'MẤT KẾT NỐI' : 'ĐANG LÀM BÀI'}</span></div><span data-testid={`score-${student.idHs}`} className={`text-xl font-bold tabular-nums ${completed ? 'text-emerald-700' : 'text-slate-600'}`}>{completed ? formatScore(student.diemSo) : '—'}</span></li>
        })}
      </ol>}
      <p className="border-t border-slate-100 px-5 py-3 text-xs leading-5 text-slate-600 sm:px-6">{stale ? 'Đang hiển thị dữ liệu ở lần cập nhật gần nhất.' : closed ? 'Phòng đã đóng. Kết quả đã lưu vẫn được giữ lại.' : 'Điểm tự cập nhật khi thí sinh nộp bài. Trung bình tính trên các bài đã hoàn thành.'}</p>
    </section>
  )
}
