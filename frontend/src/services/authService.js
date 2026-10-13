import apiClient from './apiClient.js'

function toTeacher(data) {
  return {
    idGv: data.id_gv,
    email: data.email,
    fullName: data.ho_ten,
    school: data.truong,
    province: data.tinh,
  }
}

export async function loginTeacher({ email, password }) {
  const { data } = await apiClient.post(
    '/api/auth/login',
    { email: email.trim(), mat_khau: password },
    { skipAuth: true },
  )
  return data
}

export async function registerTeacher({ email, password, fullName, school, province }) {
  const { data } = await apiClient.post('/api/auth/register', {
    email: email.trim(),
    mat_khau: password,
    ho_ten: fullName.trim(),
    truong: school.trim() || null,
    tinh: province.trim() || null,
  }, { skipAuth: true })
  return toTeacher(data)
}

export async function getCurrentTeacher() {
  const { data } = await apiClient.get('/api/auth/me')
  return toTeacher(data)
}
