"""FastAPI entry point. Run Uvicorn from the backend directory."""

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
import socketio

from app.core.config import get_auth_settings
from app.core.socket_manager import sio, socket_manager
from app.api.auth import router as auth_router
from app.api.exam import router as exam_router
from app.api.health import router as health_router
from app.api.room import router as room_router
from app.api.submission import router as submission_router

@asynccontextmanager
async def lifespan(app: FastAPI):
    get_auth_settings()
    try:
        yield
    finally:
        await socket_manager.shutdown()


fastapi_app = FastAPI(
    title="AI Quiz & Exam API",
    description="API for the AI quiz generator and online exam system.",
    version="0.1.0",
    lifespan=lifespan,
)

fastapi_app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:5173",
        "http://localhost:3000",
        "https://frontend-production-2db2d.up.railway.app",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

fastapi_app.include_router(health_router, prefix="/api")
fastapi_app.include_router(auth_router, prefix="/api")
fastapi_app.include_router(exam_router, prefix="/api")
fastapi_app.include_router(room_router, prefix="/api")
fastapi_app.include_router(submission_router, prefix="/api")

# Engine.IO handles /socket.io; all REST/docs/lifespan traffic goes to FastAPI.
app = socketio.ASGIApp(sio, other_asgi_app=fastapi_app)
