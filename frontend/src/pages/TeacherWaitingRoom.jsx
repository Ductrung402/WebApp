import { useEffect, useState } from 'react'
import { Clock3, DoorOpen, LoaderCircle, LockKeyhole, Play, RefreshCw } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import FormMessage from '../components/FormMessage.jsx'
import SocketConnectionStatus from '../components/SocketConnectionStatus.jsx'
import RoomCodePanel from '../components/rooms/RoomCodePanel.jsx'
import RoomLoading from '../components/rooms/RoomLoading.jsx'
import StudentRoster from '../components/rooms/StudentRoster.jsx'
import TeacherLiveLeaderboard from '../components/rooms/TeacherLiveLeaderboard.jsx'
import CloseRoomDialog from '../components/rooms/CloseRoomDialog.jsx'
import useRoomPolling from '../hooks/useRoomPolling.js'
import useExamSocket from '../hooks/useExamSocket.js'
import { ROOM_STATUS } from '../utils/roomValidation.js'
import { closeExamRoom, startExam } from '../services/socket.js'
import useExamCountdown from '../hooks/useExamCountdown.js'
import ExamCountdown from '../components/ExamCountdown.jsx'

export default function TeacherWaitingRoom() {
  const { id } = useParams()
  const { room, loading, refreshing, error, updatedAt, retry, mergeStudents, markClosed } = useRoomPolling(id)
  const [closing, setClosing] = useState(false)
  const [closeDialog, setCloseDialog] = useState(false)
  const [closeError, setCloseError] = useState('')
  const closed = room?.trangThaiPhong === 'DA_DONG'
  const connection = useExamSocket(room && (!closed || closing) ? { maCodePhongThi: room.maCodePhongThi, userType: 'teacher' } : null, mergeStudents, markClosed)
  const seconds = useExamCountdown(connection.exam, connection.clock)
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState('')
  const active = !closed && (Boolean(connection.exam) || room?.trangThaiPhong === 'DANG_THI')
  const status = ROOM_STATUS[closed ? 'DA_DONG' : active ? 'DANG_THI' : room?.trangThaiPhong] || { label: 'Chưa có trạng thái', className: 'bg-slate-100 text-slate-600 ring-slate-200' }
  useEffect(() => { setStarting(false); setStartError(''); setClosing(false); setCloseDialog(false); setCloseError('') }, [id])
  async function beginExam() {
    if (starting || active || connection.status !== 'joined' || !connection.syncReady) return
    setStarting(true)
    setStartError('')
    try { await startExam(room.maCodePhongThi) } catch (error) { setStartError(error.message) }
    finally { setStarting(false) }
  }
  async function finishRoom() {
    if (closing || closed || connection.status !== 'joined') return
    setClosing(true)
    setCloseError('')
    try { const packet = await closeExamRoom(room.maCodePhongThi); markClosed(packet); setCloseDialog(false); retry() }
    catch (error) { setCloseError(error.message) }
    finally { setClosing(false) }
  }

  return (
    <>
      <div className="mb-7 flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0"><p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">Phòng thi của bạn</p><h1 className="mt-3 break-words text-3xl font-bold tracking-tight">{room ? `${closed ? 'Phòng đã đóng' : active ? 'Theo dõi thi' : 'Phòng chờ'} · ${room.tenLop}` : 'Phòng chờ giáo viên'}</h1></div>
        <Link to="/rooms/create" className="button-secondary"><DoorOpen size={16} aria-hidden="true" /> Tạo phòng khác</Link>
      </div>
      {loading ? <RoomLoading /> : (
        <>
          {error && <div className="mb-5 space-y-3"><FormMessage>{error}</FormMessage><button type="button" onClick={retry} className="button-secondary"><RefreshCw size={16} aria-hidden="true" /> Thử lại</button></div>}
          {room && (
            <>
              <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
                <span className={`rounded-full px-3 py-1.5 text-xs font-semibold ring-1 ring-inset ${status.className}`}>{status.label}</span>
                <span className="inline-flex items-center gap-2 text-xs text-slate-600">{refreshing ? <LoaderCircle size={14} className="animate-spin" aria-hidden="true" /> : <span className={`h-2 w-2 rounded-full ${error ? 'bg-amber-500' : room.trangThaiPhong === 'DA_DONG' ? 'bg-slate-400' : 'bg-emerald-500'}`} aria-hidden="true" />}{error ? 'Đang kết nối lại' : refreshing ? 'Đang cập nhật danh sách…' : room.trangThaiPhong === 'DA_DONG' ? 'Phòng đã đóng' : 'Danh sách tự động cập nhật'}</span>
              </div>
              <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_280px]">
                <div className="min-w-0 space-y-6"><RoomCodePanel code={room.maCodePhongThi} />{active || closed ? <TeacherLiveLeaderboard students={room.hocSinhList} code={room.maCodePhongThi} stale={Boolean(error)} closed={closed} /> : <StudentRoster students={room.hocSinhList} updatedAt={updatedAt} stale={Boolean(error)} closed={closed} />}</div>
                <aside className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
                  <h2 className="font-semibold">Thông tin buổi thi</h2>
                  <div className="mt-3"><SocketConnectionStatus {...connection} roomLabel={active ? 'phòng thi' : 'phòng chờ'} /></div>
                  {active && <div className="mt-5"><ExamCountdown seconds={seconds} /></div>}
                  <dl className="mt-5 space-y-5"><div><dt className="text-xs text-slate-600">Lớp / nhóm</dt><dd className="mt-2 break-words text-sm font-semibold">{room.tenLop}</dd></div><div><dt className="text-xs text-slate-600">Thời gian làm bài</dt><dd className="mt-2 flex items-center gap-2 text-sm font-semibold"><Clock3 size={16} className="text-brand-700" aria-hidden="true" />{room.thoiGianLamBai} phút</dd></div></dl>
                  <div className="mt-6 border-t border-slate-100 pt-6"><FormMessage>{startError}</FormMessage><button type="button" onClick={beginExam} disabled={starting || active || room.trangThaiPhong !== 'DANG_CHO' || connection.status !== 'joined' || !connection.syncReady} aria-describedby="start-room-hint" className="button-primary mt-3 w-full !py-4">{starting ? <LoaderCircle size={18} className="animate-spin" aria-hidden="true" /> : <Play size={18} aria-hidden="true" />}{starting ? 'Đang bắt đầu…' : 'Bắt đầu làm bài'}</button><p id="start-room-hint" className="mt-3 text-xs leading-5 text-slate-600">{active ? seconds === 0 ? 'Đã hết thời gian làm bài.' : 'Phòng thi đang diễn ra.' : room.trangThaiPhong === 'DA_DONG' ? 'Phòng thi đã đóng.' : connection.status === 'joined' && connection.syncReady ? 'Bắt đầu sẽ gửi đề thi và chạy đồng hồ cho cả lớp.' : 'Đợi kết nối phòng chờ để bắt đầu.'}</p></div>
                  {!closed && <button type="button" onClick={() => { setCloseError(''); setCloseDialog(true) }} disabled={closing || starting || connection.status !== 'joined'} className="button-secondary mt-5 w-full !text-rose-700"><LockKeyhole size={16} aria-hidden="true" />Đóng phòng thi</button>}
                </aside>
              </div>
              <CloseRoomDialog open={closeDialog} busy={closing} error={closeError} pendingCount={room.hocSinhList.filter((student) => student.trangThai !== 'HOAN_THANH').length} onCancel={() => { if (!closing) setCloseDialog(false) }} onConfirm={finishRoom} />
            </>
          )}
        </>
      )}
    </>
  )
}
