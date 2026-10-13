import apiClient from './apiClient.js'
import { normalizeRoomCode } from '../utils/studentValidation.js'

export async function joinStudentRoom(values, signal) {
  const { data } = await apiClient.post('/api/rooms/join', {
    hoVaTen: values.hoVaTen.trim(),
    maCodePhongThi: normalizeRoomCode(values.maCodePhongThi),
  }, { signal, skipAuth: true })
  return data
}
