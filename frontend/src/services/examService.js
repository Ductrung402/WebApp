import apiClient from './apiClient.js'
import { QUESTION_FIELDS } from '../utils/examValidation.js'

export function toEditableExam(exam) {
  return {
    tenDeThi: exam.tenDeThi,
    khoi: exam.khoi || '',
    kieuTaiLieu: exam.kieuTaiLieu || 'TEXT',
    noiDungTaiLieu: exam.noiDungTaiLieu ?? null,
    cauHoiList: exam.cauHoiList.map((question) => Object.fromEntries(QUESTION_FIELDS.map((key) => [key, question[key]]))),
  }
}

export function toExamPayload(draft) {
  return {
    tenDeThi: draft.tenDeThi.trim(),
    khoi: draft.khoi?.trim() || null,
    kieuTaiLieu: draft.kieuTaiLieu,
    noiDungTaiLieu: draft.noiDungTaiLieu,
    cauHoiList: draft.cauHoiList.map((question) => Object.fromEntries(QUESTION_FIELDS.map((key) => [key, question[key].trim()]))),
  }
}

export async function listExams(signal) {
  const { data } = await apiClient.get('/api/exams', { signal })
  return data
}

export async function previewAiExam(values, signal) {
  const { data } = await apiClient.post('/api/exams/preview-ai', {
    tenDeThi: values.tenDeThi.trim(),
    khoi: values.khoi || null,
    rawText: values.rawText,
    numQuestions: Number(values.numQuestions),
  }, { signal, timeout: 20000 })
  return toEditableExam(data)
}

export async function getExam(examId, signal) {
  const { data } = await apiClient.get(`/api/exams/${encodeURIComponent(examId)}`, { signal })
  return data
}

export async function saveNewExam(draft) {
  const { data } = await apiClient.post('/api/exams', toExamPayload(draft))
  return data
}

export async function updateExam(examId, draft) {
  const { data } = await apiClient.put(`/api/exams/${encodeURIComponent(examId)}`, toExamPayload(draft))
  return data
}

export async function deleteExam(examId) {
  const { data } = await apiClient.delete(`/api/exams/${encodeURIComponent(examId)}`)
  return data
}
