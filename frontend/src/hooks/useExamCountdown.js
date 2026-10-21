import { useEffect, useRef, useState } from 'react'
import { remainingSeconds } from '../utils/examRun.js'

export default function useExamCountdown(exam, clock, frozen = false) {
  const [, render] = useState(0)
  const last = useRef({ runId: null, seconds: null })
  useEffect(() => {
    if (!exam || !clock || frozen) return
    const tick = () => render((value) => value + 1)
    const timer = window.setInterval(tick, 250)
    document.addEventListener('visibilitychange', tick)
    return () => { window.clearInterval(timer); document.removeEventListener('visibilitychange', tick) }
  }, [exam, clock, frozen])
  if (!exam || !clock) return null
  if (last.current.runId !== exam.runId || !frozen) last.current = { runId: exam.runId, seconds: remainingSeconds(exam, clock) }
  return last.current.seconds
}
