import { expect } from '@playwright/test'

const BASE = Date.UTC(2026, 9, 13, 3)
const student = { idHs: 'student-one', hoVaTen: 'Nguyễn Văn An', maCodePhongThi: 'AB12CD', idPhongThi: 'room-one', tenLop: '6A', thoiGianLamBai: 1 }
const question = { idCauHoi: 'q-1', noiDung: 'Công thức hóa học của nước là gì?', dapAnA: 'H2O', dapAnB: 'CO2', dapAnC: 'O2', dapAnD: 'NaCl' }
const makeRun = (startsAt = BASE) => ({ runId: 'run-one', idPhongThi: 'room-one', idDeThi: 'exam-one', maCodePhongThi: 'AB12CD', tenDeThi: 'Kiến thức về nước', tenLop: '6A', thoiGianLamBai: 1, trangThaiPhong: 'DANG_THI', startsAt, endsAt: startsAt + 60000, serverTime: BASE, cauHoiList: [question] })
const cors = { 'access-control-allow-origin': 'http://localhost:5173', 'access-control-allow-methods': 'GET, OPTIONS', 'access-control-allow-headers': 'authorization,content-type' }

async function setup(page, options = {}) {
  await page.clock.install({ time: new Date(BASE) })
  await page.clock.pauseAt(new Date(BASE + 10000))
  await page.addInitScript(({ student, teacher, anonymous }) => {
    if (!anonymous) sessionStorage.setItem('quizAi.studentSession', JSON.stringify(student))
    if (teacher) localStorage.setItem('quizAi.teacherAccessToken', 'valid-test-token')
  }, { student, teacher: options.teacher || false, anonymous: options.anonymous || false })
  const state = { run: null, serverNow: BASE, sockets: [], calls: [], resultReads: [], result: null, receipt: null, scores: [], closed: false, closeStatus: 200, submitStatus: 200, startStatus: 200, ...options }
  await page.route('**/api/**', async (route) => {
    if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
    const path = new URL(route.request().url()).pathname
    if (path === '/api/submissions/result') {
      state.resultReads.push({ url: route.request().url(), headers: route.request().headers() })
      if (state.resultGate) await state.resultGate
      const result = state.result || (state.studentStatus === 'HOAN_THANH' ? { idHs: student.idHs, diemSo: 10, soCauDung: 1, tongSoCau: 1, trangThai: 'HOAN_THANH' } : null)
      return route.fulfill({ status: state.resultStatus || (result ? 200 : 409), headers: cors, contentType: 'application/json', body: JSON.stringify(state.resultStatus && state.resultStatus !== 200 ? { detail: 'Unavailable' } : result || { detail: 'Not graded' }) })
    }
    const body = path === '/api/auth/me' ? { id_gv: 'teacher', email: 'teacher@example.com', ho_ten: 'Nguyễn Văn A', truong: null, tinh: null }
      : { idPhongThi: 'room-one', maCodePhongThi: 'AB12CD', tenLop: '6A', thoiGianLamBai: 1, trangThaiPhong: state.closed ? 'DA_DONG' : state.run ? 'DANG_THI' : 'DANG_CHO', ngayTaoPhong: '2026-10-13', hocSinhList: state.students || [student], ketQuaList: state.scores }
    return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify(body) })
  })
  const currentRun = () => state.run ? { ...state.run, serverTime: state.serverNow } : null
  await page.routeWebSocket('**/socket.io/**', (ws) => {
    state.sockets.push(ws)
    ws.onMessage(async (message) => {
      const text = String(message)
      if (text.startsWith('40')) ws.send('40' + JSON.stringify({ sid: `socket-${state.sockets.length}` }))
      else if (text.startsWith('42')) {
        const index = text.indexOf('[')
        const ackId = text.slice(2, index)
        const [event, payload] = JSON.parse(text.slice(index))
        const receivedAt = state.serverNow
        state.calls.push({ event, payload })
        let ack
        if (event === 'join_exam_room') ack = { status: 'ok', ...payload, idPhongThi: 'room-one' }
        else if (event === 'sync_exam' || event === 'reconnect_exam') {
          if (state.syncGate) await state.syncGate
          if (event === 'reconnect_exam' && state.recoveryGate) await state.recoveryGate
          ack = state.syncStatus === 503 || (event === 'reconnect_exam' && state.recoveryStatus === 503) ? { status: 'error', statusCode: 503 } : { status: 'ok', serverTime: state.serverNow, requestReceivedAt: receivedAt, exam: currentRun(), submission: state.receipt,
            studentState: { ...student, idPhongThi: 'room-one', trangThai: state.studentStatus || 'DANG_THI', stateVersion: 2, stateChangedAt: state.serverNow },
            remainingSeconds: state.run ? Math.max(0, Math.ceil((state.run.endsAt - state.serverNow) / 1000)) : null, examExpired: Boolean(state.run && state.serverNow >= state.run.endsAt) }
        }
        else if (event === 'start_exam') {
          if (state.startStatus !== 200) ack = { status: 'error', statusCode: state.startStatus }
          else {
            state.run ||= makeRun(state.serverNow)
            state.emitStarted()
            ack = { status: 'ok', serverTime: state.serverNow, exam: currentRun(), submission: null }
          }
        } else if (event === 'submit_exam') {
          if (state.submitGate) await state.submitGate
          if (state.submitStatus !== 200) ack = { status: 'error', statusCode: state.submitStatus }
          else {
            state.receipt ||= { submissionId: 'submission-one', runId: payload.runId, idHs: student.idHs, receivedAt: state.serverNow, receivedLate: state.serverNow > state.run.endsAt, reason: payload.reason, duplicate: false }
            state.result ||= { idHs: student.idHs, diemSo: payload.answers['q-1'] === 'A' ? 10 : 0, soCauDung: payload.answers['q-1'] === 'A' ? 1 : 0, tongSoCau: 1, trangThai: 'HOAN_THANH' }
            state.studentStatus = 'HOAN_THANH'
            ack = { status: 'ok', submission: state.receipt, result: state.result }
          }
        } else if (event === 'close_exam_room') {
          if (state.closeGate) await state.closeGate
          if (state.closeStatus !== 200) ack = { status: 'error', statusCode: state.closeStatus }
          else { state.emitRoomClosed(); ack = { status: 'ok', idPhongThi: 'room-one', maCodePhongThi: 'AB12CD', trangThaiPhong: 'DA_DONG' } }
        } else throw new Error(`Unexpected event ${event}`)
        ws.send('43' + ackId + JSON.stringify([ack]))
      }
    })
    ws.send('0' + JSON.stringify({ sid: 'engine-sid', upgrades: [], pingInterval: 1000000000, pingTimeout: 1000000000, maxPayload: 1000000 }))
  })
  state.emitStarted = (packet = currentRun()) => state.sockets.at(-1).send('42' + JSON.stringify(['exam_started', packet]))
  state.emitRoomClosed = (code = 'AB12CD') => { if (code === 'AB12CD') state.closed = true; state.sockets.at(-1).send('42' + JSON.stringify(['room_closed', { idPhongThi: 'room-one', maCodePhongThi: code, trangThaiPhong: 'DA_DONG' }])) }
  return state
}


export { setup, student, question, makeRun, BASE }
