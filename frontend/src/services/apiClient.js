import axios from 'axios'
import { AUTH_EXPIRED_EVENT, getAccessToken, removeAccessToken } from '../utils/tokenStorage.js'

const apiClient = axios.create({
  baseURL: (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8000').replace(/\/+$/, ''),
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

apiClient.interceptors.request.use((config) => {
  if (!config.skipAuth) {
    const token = getAccessToken()
    if (token) config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const token = getAccessToken()
    if (
      error.response?.status === 401 && !error.config?.skipAuth && token &&
      error.config?.headers?.Authorization === `Bearer ${token}`
    ) {
      removeAccessToken()
      window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT))
    }
    return Promise.reject(error)
  },
)

export default apiClient
