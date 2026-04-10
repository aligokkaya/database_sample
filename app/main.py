"""
LLM-Based Database Data Discovery System
FastAPI application entry point
"""
from contextlib import asynccontextmanager
from typing import AsyncGenerator

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.database import init_db
from app.auth.router import router as auth_router
from app.metadata.router import router as metadata_router
from app.classify.router import router as classify_router


# ---------- Lifespan ----------

@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Initialise the system database tables on startup."""
    await init_db()
    yield


# ---------- Application ----------

app = FastAPI(
    title="LLM-Based Database Data Discovery System",
    description=(
        "Discover and classify PII in any PostgreSQL database using LLMs. "
        "Connect to target databases, extract schema metadata, and classify "
        "columns across 13 PII categories with probability distributions."
    ),
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
    lifespan=lifespan,
)

# ---------- CORS ----------

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------- Routers ----------

app.include_router(auth_router)       # POST /auth
app.include_router(metadata_router)   # POST /db/metadata, GET/DELETE /metadata[/{id}]
app.include_router(classify_router)   # POST /classify


# ---------- Health check ----------

@app.get("/health", tags=["Health"], summary="Health check")
async def health_check() -> dict[str, str]:
    """Returns the service health status. No authentication required."""
    return {"status": "healthy", "service": "data-discovery-api"}
