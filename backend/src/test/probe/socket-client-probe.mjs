// Opt-in T0.3 probe. Imports the installed Frontend client, no npm changes/.env.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { writeFile, mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

const requireFrontend = createRequire(resolve('../frontend/package.json'))
const { io } = requireFrontend('socket.io-client')
const { protocol } = requireFrontend('socket.io-parser')
const version = (name) => requireFrontend(`${name}/package.json`).version
const [url, output] = process.argv.slice(2)
assert.match(url, /^http:\/\/127\.0\.0\.1:\d+$/)
const fixtureToken = 'synthetic-t03-auth-only-not-a-jwt'
const code = 'AB12CD'
const report = { task: 'T0.3', syntheticOnly: true, node: process.version,
  socketIoClient: version('socket.io-client'), engineIoClient: version('engine.io-client'),
  socketIoProtocol: protocol, cases: [] }
const pause = (ms) => new Promise((resolvePause) => setTimeout(resolvePause, ms))

function summary(packet) {
  const result = { engineType: packet.type }
  if (packet.type === 'open') {
    try { result.engineOpenHasSid = typeof JSON.parse(packet.data).sid === 'string' } catch { result.invalidOpen = true }
  }
  if (packet.type === 'message' && typeof packet.data === 'string') {
    result.socketPacketType = packet.data.charAt(0)
    if (packet.data.charAt(0) === '0') {
      try {
        const data = packet.data.length > 1 ? JSON.parse(packet.data.slice(1)) : null
        result.connectPayloadKeys = data ? Object.keys(data) : []
        result.connectHasSid = typeof data?.sid === 'string'
        result.connectHasSyntheticAuth = data?.token === fixtureToken
      } catch { result.invalidConnectPayload = true }
    }
  }
  return result // Never write raw frames, token values, headers or arbitrary payloads.
}

async function runCase(role) {
  const item = { role, engineOpen: false, namespaceConnected: false,
    authSentInConnect: false, joinAck: 'NOT_RUN', reconnect: 'NOT_RUN', rejoinAck: 'NOT_RUN',
    packetsIn: [], packetsOut: [], errors: [], disconnects: [], reconnectAttempts: 0,
    joinAcks: [], connectionSids: [] }
  const client = io(url, { autoConnect: false, forceNew: true, transports: ['websocket'],
    reconnection: true, reconnectionAttempts: 2, reconnectionDelay: 200,
    reconnectionDelayMax: 200, randomizationFactor: 0, timeout: 2500,
    auth: role === 'teacher' ? { token: fixtureToken } : {} })
  client.io.on('open', () => { item.engineOpen = true; item.transport = client.io.engine.transport.name })
  client.on('connect', () => { item.connectionSids.push(client.id) })
  client.io.on('reconnect_attempt', () => { item.reconnectAttempts++ })
  client.on('disconnect', (reason) => { item.disconnects.push(reason) })
  client.on('connect_error', (error) => { item.errors.push(String(error.message).replaceAll(fixtureToken, '[redacted]')) })

  function attachWireObservation() {
    const engine = client.io.engine
    engine.on('packet', (packet) => { item.packetsIn.push(summary(packet)) })
    engine.on('packetCreate', (packet) => {
      const safe = summary(packet)
      if (safe.connectHasSyntheticAuth) item.authSentInConnect = true
      item.packetsOut.push(safe)
    })
  }
  async function waitForConnect(action) {
    return await new Promise((resolveConnect) => {
      const timer = setTimeout(() => finish(false), 3500)
      const onConnected = () => finish(true)
      const onError = () => finish(false)
      function finish(value) {
        clearTimeout(timer)
        client.off('connect', onConnected)
        client.off('connect_error', onError)
        resolveConnect(value)
      }
      client.once('connect', onConnected)
      client.once('connect_error', onError)
      action()
    })
  }
  async function join() {
    const packet = { maCodePhongThi: code, userType: role,
      idHs: role === 'student' ? 'fixture-student-t03' : null }
    const ack = await client.timeout(2000).emitWithAck('join_exam_room', packet)
    assert.deepEqual(ack, { ...packet, status: 'ok', idPhongThi: 'fixture-room-t03' })
    item.joinAcks.push(ack) // Only synthetic fixture IDs, no token or exam payload.
    return 'PASS'
  }
  try {
    const connected = await waitForConnect(() => { client.connect(); attachWireObservation() })
    item.namespaceConnected = connected
    if (!connected) {
      item.failureStage = 'socket_io_namespace_connect'
      return item
    }
    item.joinAck = await join()
    const oldSid = client.id
    // Drop only transport; automatic Manager reconnect must run, not manual .connect().
    const reconnected = await waitForConnect(() => client.io.engine.close())
    item.reconnect = reconnected && client.id !== oldSid && item.reconnectAttempts > 0 ? 'PASS' : 'FAIL'
    if (item.reconnect === 'PASS') item.rejoinAck = await join()
  } catch (error) {
    item.failureStage = 'ack_or_reconnect'
    item.errors.push(String(error.message).replaceAll(fixtureToken, '[redacted]'))
    if (item.joinAck === 'NOT_RUN') item.joinAck = 'FAIL'
  } finally {
    client.removeAllListeners()
    client.disconnect()
    await pause(100)
    item.clientClosed = !client.connected && client.io.engine?.readyState === 'closed'
  }
  return item
}

try {
  // Both roles are attempted independently even if the first fails protocol negotiation.
  for (const role of ['teacher', 'student']) report.cases.push(await runCase(role))
  report.allPassed = report.cases.every((item) => item.namespaceConnected && item.joinAck === 'PASS'
    && item.reconnect === 'PASS' && item.rejoinAck === 'PASS' && item.clientClosed)
} catch (error) {
  report.allPassed = false
  report.infrastructureError = error.constructor.name
} finally {
  await mkdir(output, { recursive: true })
  await writeFile(resolve(output, 'client-result.json'), JSON.stringify(report, null, 2) + '\n')
  for (const item of report.cases) console.log(JSON.stringify({ role: item.role,
    transport: item.transport, namespaceConnected: item.namespaceConnected, authSentInConnect: item.authSentInConnect,
    joinAck: item.joinAck, reconnect: item.reconnect, rejoinAck: item.rejoinAck, errors: item.errors }))
  process.exitCode = report.allPassed ? 0 : 2
}
