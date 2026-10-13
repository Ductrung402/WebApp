export const PUBLIC_QUESTION_FIELDS = ['idCauHoi', 'noiDung', 'dapAnA', 'dapAnB', 'dapAnC', 'dapAnD']
const runFields = ['runId', 'idPhongThi', 'idDeThi', 'maCodePhongThi', 'tenDeThi', 'tenLop', 'thoiGianLamBai', 'trangThaiPhong', 'startsAt', 'endsAt', 'serverTime']

export function parsePublicExam(value) {
  if (!value || value.trangThaiPhong !== 'DANG_THI' || !/^[A-Z0-9]{6}$/.test(value.maCodePhongThi)) return null
  if (!['runId', 'idPhongThi', 'idDeThi', 'tenDeThi', 'tenLop'].every((key) => typeof value[key] === 'string' && value[key].trim())) return null
  if (!['startsAt', 'endsAt', 'serverTime', 'thoiGianLamBai'].every((key) => Number.isSafeInteger(value[key]) && value[key] >= 0)) return null
  if (value.thoiGianLamBai < 1 || value.endsAt - value.startsAt !== value.thoiGianLamBai * 60000) return null
  if (!Array.isArray(value.cauHoiList) || !value.cauHoiList.length) return null
  if (!value.cauHoiList.every((question) => question && !('dapAnDung' in question) && !('dap_an_dung' in question) && PUBLIC_QUESTION_FIELDS.every((key) => typeof question[key] === 'string' && question[key].trim()))) return null
  if (new Set(value.cauHoiList.map((question) => question.idCauHoi)).size !== value.cauHoiList.length) return null
  return {
    ...Object.fromEntries(runFields.map((key) => [key, value[key]])),
    cauHoiList: value.cauHoiList.map((question) => Object.fromEntries(PUBLIC_QUESTION_FIELDS.map((key) => [key, question[key]]))),
  }
}

export function remainingSeconds(exam, clock) {
  if (!exam || !clock) return null
  const serverNow = clock.serverNow + (performance.now() - clock.monotonicAt)
  return Math.max(0, Math.ceil((exam.endsAt - serverNow) / 1000))
}

export function createClockAnchor(serverTime, requestReceivedAt, before, after) {
  const processing = Number.isSafeInteger(requestReceivedAt) ? Math.max(0, serverTime - requestReceivedAt) : 0
  const networkRoundTrip = Math.max(0, after - before - processing)
  return { serverNow: serverTime + networkRoundTrip / 2, monotonicAt: after }
}

export function formatCountdown(seconds) {
  return seconds === null ? '--:--' : `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}
