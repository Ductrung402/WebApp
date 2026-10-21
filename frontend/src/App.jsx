import { Route, Routes } from 'react-router-dom'
import ProtectedRoute from './components/ProtectedRoute.jsx'
import PublicRoute from './components/PublicRoute.jsx'
import TeacherLayout from './layouts/TeacherLayout.jsx'
import DashboardPage from './pages/DashboardPage.jsx'
import LoginPage from './pages/LoginPage.jsx'
import NotFoundPage from './pages/NotFoundPage.jsx'
import RegisterPage from './pages/RegisterPage.jsx'
import ExamsPage from './pages/ExamsPage.jsx'
import ExamCreateAIPage from './pages/ExamCreateAIPage.jsx'
import ExamDetailPage from './pages/ExamDetailPage.jsx'
import ExamEditPage from './pages/ExamEditPage.jsx'
import RoomCreatePage from './pages/RoomCreatePage.jsx'
import TeacherWaitingRoom from './pages/TeacherWaitingRoom.jsx'
import StudentLayout from './layouts/StudentLayout.jsx'
import StudentJoin from './pages/StudentJoin.jsx'
import StudentWaiting from './pages/StudentWaiting.jsx'
import StudentTaking from './pages/StudentTaking.jsx'
import StudentResult from './pages/StudentResult.jsx'

export default function App() {
  return (
    <Routes>
      <Route element={<StudentLayout />}>
        <Route path="/" element={<StudentJoin />} />
        <Route path="/join" element={<StudentJoin />} />
        <Route path="/exam-room/:code/waiting" element={<StudentWaiting />} />
        <Route path="/exam-room/:code/taking" element={<StudentTaking />} />
        <Route path="/exam-room/:code/result" element={<StudentResult />} />
      </Route>
      <Route element={<PublicRoute />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
      </Route>
      <Route element={<ProtectedRoute />}>
        <Route element={<TeacherLayout />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/exams" element={<ExamsPage />} />
          <Route path="/exams/create-ai" element={<ExamCreateAIPage />} />
          <Route path="/exams/:examId" element={<ExamDetailPage />} />
          <Route path="/exams/:examId/edit" element={<ExamEditPage />} />
          <Route path="/rooms/create" element={<RoomCreatePage />} />
          <Route path="/rooms/:id/waiting" element={<TeacherWaitingRoom />} />
        </Route>
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
