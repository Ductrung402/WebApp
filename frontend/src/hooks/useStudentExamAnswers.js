import { useEffect, useRef, useState } from 'react'
import { submitExam } from '../services/socket.js'
import { remainingSeconds } from '../utils/examRun.js'
import { answerStorageKey, readAnswerDraft, validReceipt, writeAnswerDraft } from '../utils/studentAnswerStorage.js'
import { parseSubmissionResult } from '../utils/results.js'

export default function useStudentExamAnswers(session, connection, seconds) {
  const exam = connection.exam
  const key = session && exam ? answerStorageKey(session.idHs, exam.runId) : ''
  const [data, setData] = useState({ key: '', answers: {}, pending: null, receipt: null })
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [storageError, setStorageError] = useState('')
  const active = useRef(true)
  const busy = useRef(false)
  const generation = useRef(0)
  const allowed = new Set(exam?.cauHoiList.map((question) => question.idCauHoi) || [])
  const current = data.key === key ? data : { key, ...readAnswerDraft(key, allowed, session?.idHs, exam?.runId) }
  const latest = useRef(current)
  latest.current = current

  useEffect(() => {
    active.current = true
    return () => { active.current = false; generation.current += 1 }
  }, [])
  useEffect(() => {
    generation.current += 1
    busy.current = false
    setSending(false)
    setError('')
    setStorageError('')
    setData({ key, ...readAnswerDraft(key, allowed, session?.idHs, exam?.runId) })
  }, [key])

  function persist(next) {
    latest.current = next
    setData(next)
    try { writeAnswerDraft(key, next); setStorageError('') }
    catch { setStorageError('Trình duyệt chưa lưu được đáp án. Hãy giữ trang này mở để tránh mất lựa chọn.') }
  }

  async function attempt() {
    const draft = latest.current
    if (!key || busy.current || draft.receipt || !draft.pending || connection.studentState?.trangThai === 'HOAN_THANH' || connection.status !== 'joined' || !connection.recoveryReady) return
    busy.current = true
    const version = generation.current
    setSending(true)
    setError('')
    try {
      const ack = await submitExam(exam.maCodePhongThi, exam.runId, draft.pending.answers, draft.pending.reason)
      if (!active.current || version !== generation.current) return
      if (!validReceipt(ack.submission, session.idHs, exam.runId)) throw new Error('Chưa nhận được xác nhận bài nộp hợp lệ.')
      persist({ ...latest.current, pending: null, receipt: ack.submission })
      const result = parseSubmissionResult(ack.result, session.idHs)
      if (result) setData((current) => ({ ...current, result }))
    } catch (error) {
      if (active.current && version === generation.current) setError(error.message || 'Chưa nhận được xác nhận bài nộp. Vui lòng thử lại.')
    } finally {
      if (active.current && version === generation.current) { busy.current = false; setSending(false) }
    }
  }

  function queue(reason) {
    if (!key || connection.closed || connection.studentState?.trangThai === 'HOAN_THANH' || latest.current.receipt || latest.current.pending) return
    persist({ ...latest.current, pending: { reason, answers: { ...latest.current.answers } } })
    if (connection.status === 'joined' && connection.recoveryReady) attempt()
    else setError('Chưa kết nối với phòng thi. Bài nộp được giữ để gửi khi kết nối trở lại.')
  }

  useEffect(() => {
    if (key && validReceipt(connection.submission, session.idHs, exam.runId) && latest.current.receipt?.submissionId !== connection.submission.submissionId) {
      persist({ ...latest.current, pending: null, receipt: connection.submission })
    }
  }, [key, connection.submission])
  useEffect(() => {
    if (key && connection.status === 'joined' && connection.recoveryReady) attempt()
  }, [key, connection.status, connection.recoveryReady])
  useEffect(() => {
    if (key && seconds === 0 && connection.recoveryReady) queue('timeout')
  }, [key, seconds, connection.recoveryReady])

  function choose(questionId, answer) {
    if (connection.status !== 'joined' || !connection.recoveryReady || connection.studentState?.trangThai === 'HOAN_THANH' || !allowed.has(questionId) || !['A', 'B', 'C', 'D'].includes(answer) || latest.current.pending || latest.current.receipt || remainingSeconds(exam, connection.clock) === 0) return
    persist({ ...latest.current, answers: { ...latest.current.answers, [questionId]: answer } })
  }
  return { ...current, sending, error, storageError, choose, submit: () => queue('manual'), retry: attempt }
}
