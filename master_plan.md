# MASTER PLAN: QUIZ AI & REAL-TIME EXAM SYSTEM

**Developer:** Solo | **Methodology:** AI-Assisted (Agentic)

## PHASE 1: Foundation, DB Setup & Authentication

**Goal:** Establish the core infrastructure and secure teacher access.

* [x] **Task 1.1: Project Initialization**
  * Set up Monorepo structure (`backend/`, `frontend/`, `docs/`).
  * Initialize FastAPI project and React (Vite + TailwindCSS) project.

* [x] **Task 1.2: Database Implementation**
  * Apply `models.py` to PostgreSQL using SQLAlchemy/Alembic.
  * *Agent Verification:* Ensure all tables and enums (`TrangThaiBaiLam`, `TrangThaiPhongThi`) are correctly mapped.

* [x] **Task 1.3: Teacher Authentication (Backend)**
  * Develop API for Teacher Registration and Login (JWT).

* [x] **Task 1.4: UI Foundation (Frontend)**
  * Build Login/Register UI.
  * Set up React Router and protected routes for the Teacher Dashboard.

## PHASE 2: AI Integration & Exam Management (CRUD)

**Goal:** Implement the core AI question generation and exam storage logic.

* [x] **Task 2.1: AI Service Integration**
  * Build `gemini_service.py` to parse text and return JSON-formatted questions.
  * *Agent Verification:* Test timeout limits and JSON schema adherence.

* [x] **Task 2.2: Exam APIs (Backend)**
  * Build CRUD endpoints for `DeThi` and `CauHoi`.
  * Implement logic to save AI-generated questions into the database.

* [x] **Task 2.3: Exam Dashboard (Frontend)**
  * Build UI for uploading text/files for AI generation.
  * Build UI to review, edit, and save AI-generated questions.
  * Display list of saved exams.

## PHASE 3: Room Management & Student Entry

**Goal:** Allow teachers to create exam sessions and students to join anonymously.

* [x] **Task 3.1: Room APIs (Backend)**
  * Build endpoint to create `PhongThi` (auto-generate 6-char `maCodePhongThi`).
  * Build endpoint for Student Join (creates `HocSinh` and `KetQuaThi` with `DANG_THI` state and score `0.0`; room remains `DANG_CHO`).

* [x] **Task 3.2: Teacher Room UI (Frontend)**
  * Build UI to select an exam and "Create Room".
  * Display the waiting room showing joined students.

* [x] **Task 3.3: Student Entry UI (Frontend)**
  * Build public landing page to input Name + Room Code.
  * Build Student Waiting Room UI.

## PHASE 4: Real-time Execution & State Management

**Goal:** Synchronize the exam state between teacher and students using Socket.io.

* [x] **Task 4.1: Socket Setup (Backend & Frontend)**
  * Initialize `python-socketio` in FastAPI and `socket.io-client` in React.

* [x] **Task 4.2: Exam Flow (Real-time)**
  * Teacher clicks "Start" -> Emit event -> Update `trang_thai_phong` to `DANG_THI`.
  * Students receive questions and the countdown timer starts.
  * Verification: `docs/exam_flow.md`, live Socket.IO payload/log and desktop/mobile screenshots.

* [x] **Task 4.3: Disconnect Handling**
  * Listen for `disconnect` event.
  * Update `KetQuaThi` to `MAT_KET_NOI` to freeze the student's timer.
  * Recover `DANG_THI` before the existing deadline; teacher receives private state updates.
  * Verification: `docs/exam_recovery.md`, real Socket.IO state payloads/log and UI screenshots.

## PHASE 5: Auto-Grading, Polish & Deployment

**Goal:** Finalize the testing flow, calculate scores, and prepare for presentation.

* [x] **Task 5.1: Submission & Auto-Grading (Backend)**
  * Build endpoint to receive student answers.
  * Compare answers with `dap_an_dung`, calculate `diem_so`, set state to `HOAN_THANH`.
  * REST and Socket submissions share grading; broadcast `student_submitted` to the room owner after commit.
  * Verification: `docs/submission.md`, actual REST/Socket payloads and 130 backend tests.

* [x] **Task 5.2: Live Leaderboard (Frontend)**
  * Update Teacher dashboard to show live scores as students submit.
  * Show final score to the student upon submission.
  * Restore student results/teacher scores after reload; close rooms via Socket.IO and export CSV.
  * Verification: `docs/leaderboard.md`, desktop/mobile screenshots, 96 frontend cases and 138 backend tests.

* [x] **Task 5.3: QA & UI Polish**
  * Test the full end-to-end flow.
  * Ensure mobile responsiveness (Tailwind) for the student view.
  * Finalize Web Programming course report.
  * Verification: `docs/QA_REPORT.md`, real Chrome/Gemini/PostgreSQL E2E (8/8), 138 backend tests, 97 frontend tests and production build.
  * Handover: root `README.md`, `docs/API_DOCUMENTATION.md`, `docs/COURSE_REPORT.md`; student mobile checked at 320/390/768px, browser console clean.
