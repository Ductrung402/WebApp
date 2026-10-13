export const answerStorageKey = (studentId, runId) => `quizAi.studentAnswers.${studentId}.${runId}`
const emptyDraft = () => ({ answers: {}, pending: null, receipt: null })

export function validReceipt(receipt, studentId, runId) {
  return receipt && receipt.idHs === studentId && receipt.runId === runId && typeof receipt.submissionId === 'string'
    && Number.isSafeInteger(receipt.receivedAt) && typeof receipt.receivedLate === 'boolean'
    && ['manual', 'timeout'].includes(receipt.reason)
}

export function readAnswerDraft(key, allowedIds, studentId, runId) {
  try {
    const value = JSON.parse(localStorage.getItem(key))
    if (!value || typeof value.answers !== 'object' || !value.answers || Array.isArray(value.answers)) return emptyDraft()
    const clean = (answers) => Object.fromEntries(Object.entries(answers).filter(([id, answer]) => allowedIds.has(id) && ['A', 'B', 'C', 'D'].includes(answer)))
    const pending = value.pending && ['manual', 'timeout'].includes(value.pending.reason) && value.pending.answers && typeof value.pending.answers === 'object'
      ? { reason: value.pending.reason, answers: clean(value.pending.answers) } : null
    return { answers: clean(value.answers), pending, receipt: validReceipt(value.receipt, studentId, runId) ? value.receipt : null }
  } catch { return emptyDraft() }
}

export function writeAnswerDraft(key, draft) {
  localStorage.setItem(key, JSON.stringify({ answers: draft.answers, pending: draft.pending, receipt: draft.receipt }))
}
