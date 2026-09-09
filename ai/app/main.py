from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="WeCall AI", version="0.1.0")


class HealthResponse(BaseModel):
    status: str


@app.get("/health", response_model=HealthResponse, tags=["health"])
def health() -> HealthResponse:
    return HealthResponse(status="ok")
