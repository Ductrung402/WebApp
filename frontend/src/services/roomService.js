import apiClient from './apiClient.js'

export async function createRoom(values, signal) {
  const { data } = await apiClient.post('/api/rooms', {
    idDeThi: values.idDeThi,
    tenLop: values.tenLop.trim(),
    thoiGianLamBai: Number(values.thoiGianLamBai),
  }, { signal })
  return data
}

export async function getRoom(roomId, signal) {
  const { data } = await apiClient.get(`/api/rooms/${encodeURIComponent(roomId)}`, { signal })
  return data
}
