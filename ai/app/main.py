from fastapi import FastAPI
from pydantic import BaseModel
from app.extraction import router as extraction_router

app = FastAPI(title="WeCall AI", version="0.1.0")
app.include_router(extraction_router)


class HealthResponse(BaseModel):
    status: str


@app.get("/health", response_model=HealthResponse, tags=["health"])
def health() -> HealthResponse:
    return HealthResponse(status="ok")
