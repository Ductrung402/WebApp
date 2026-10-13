import { useEffect, useState } from 'react'
import { getExam } from '../services/examService.js'
import { getExamError } from '../utils/examValidation.js'

export default function useExamDetail(examId) {
  const [exam, setExam] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [version, setVersion] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setExam(null)
    setError('')
    getExam(examId, controller.signal).then((data) => {
      if (!controller.signal.aborted) setExam(data)
    }).catch((error) => {
      if (!controller.signal.aborted) setError(getExamError(error))
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false)
    })
    return () => controller.abort()
  }, [examId, version])
  return { exam, loading, error, retry: () => setVersion((value) => value + 1) }
}
