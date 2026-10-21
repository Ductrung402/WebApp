import { useCallback, useEffect, useState } from 'react'
import { getRoom } from '../services/roomService.js'
import { getRoomError } from '../utils/roomValidation.js'
import { parseScoreRow, validScore } from '../utils/results.js'

export const ROOM_POLL_INTERVAL = 3000

const initialState = (roomId) => ({ roomId, room: null, loading: true, refreshing: false, error: '', updatedAt: null })
const mergeRoster = (previous, incoming) => {
  const students = new Map(previous.map((student) => [student.idHs, student]))
  for (const student of incoming) {
    const current = students.get(student.idHs)
    if (current?.trangThai === 'HOAN_THANH' && validScore(current.diemSo)) {
      students.set(student.idHs, { ...current, hoVaTen: student.hoVaTen })
      continue
    }
    // Stale snapshots and REST responses cannot erase newer Socket state.
    if (current?.trangThai && student.trangThai && (student.stateVersion < current.stateVersion || (student.stateVersion === current.stateVersion && student.trangThai !== current.trangThai))) continue
    students.set(student.idHs, { ...current, ...student })
  }
  return [...students.values()]
}

export default function useRoomPolling(roomId) {
  const [state, setState] = useState(() => initialState(roomId))
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let disposed = false
    let busy = false
    let terminal = false
    let timer
    let request
    setState((current) => current.roomId === roomId
      ? { ...current, loading: !current.room, error: '' }
      : initialState(roomId))

    async function load() {
      if (disposed || busy || terminal || document.hidden) return
      busy = true
      request = new AbortController()
      setState((current) => ({ ...current, refreshing: Boolean(current.room) }))
      try {
        const room = await getRoom(roomId, request.signal)
        if (disposed || request.signal.aborted) return
        terminal = room.trangThaiPhong === 'DA_DONG'
        const incoming = [...room.hocSinhList, ...(room.ketQuaList || []).map((row) => parseScoreRow(row, room.maCodePhongThi)).filter(Boolean)]
        setState((current) => ({
          roomId,
          room: current.roomId === roomId && current.room?.maCodePhongThi === room.maCodePhongThi
            ? { ...room, trangThaiPhong: current.room.trangThaiPhong === 'DA_DONG' ? 'DA_DONG' : room.trangThaiPhong, hocSinhList: mergeRoster(current.room.hocSinhList, incoming) } : { ...room, hocSinhList: mergeRoster([], incoming) },
          loading: false, refreshing: false, error: '', updatedAt: new Date(),
        }))
      } catch (error) {
        if (disposed || request.signal.aborted) return
        terminal = [401, 403, 404].includes(error.response?.status)
        setState((current) => ({
          ...current, room: terminal ? null : current.room,
          loading: false, refreshing: false, error: getRoomError(error),
        }))
      } finally {
        busy = false
        // Schedule after completion: slow requests never overlap.
        if (!disposed && !terminal && !document.hidden) timer = window.setTimeout(load, ROOM_POLL_INTERVAL)
      }
    }

    function handleVisibility() {
      window.clearTimeout(timer)
      if (document.hidden) request?.abort()
      else load()
    }
    load()
    document.addEventListener('visibilitychange', handleVisibility)
    return () => {
      disposed = true
      window.clearTimeout(timer)
      request?.abort()
      document.removeEventListener('visibilitychange', handleVisibility)
    }
  }, [roomId, version])

  const current = state.roomId === roomId ? state : initialState(roomId)
  const mergeStudents = useCallback((packet) => {
    setState((current) => {
      if (current.roomId !== roomId || current.room?.maCodePhongThi !== packet.maCodePhongThi) return current
      const students = mergeRoster(current.room.hocSinhList, packet.hocSinhList).sort((a, b) => a.hoVaTen.localeCompare(b.hoVaTen, 'vi'))
      return { ...current, room: { ...current.room, hocSinhList: students }, updatedAt: new Date() }
    })
  }, [roomId])
  const markClosed = useCallback((packet) => {
    setState((current) => current.roomId === roomId && current.room?.maCodePhongThi === packet.maCodePhongThi && packet.idPhongThi === roomId
      ? { ...current, room: { ...current.room, trangThaiPhong: 'DA_DONG' } } : current)
  }, [roomId])
  return { ...current, mergeStudents, markClosed, retry: () => setVersion((value) => value + 1) }
}
