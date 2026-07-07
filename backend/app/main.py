from fastapi import FastAPI

from app.api.v1.router import api_router


app = FastAPI(
    title="AI Adaptive Traffic Signal Control API",
    version="0.1.0",
)

app.include_router(api_router, prefix="/api/v1")
