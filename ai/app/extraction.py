"""Provider boundary. Fixture mode is an integration aid, not an AI model."""
import hashlib
import hmac
import json
import os
from pathlib import Path
from typing import Literal, Protocol
from uuid import UUID

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

router = APIRouter()


class ExtractionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    requestId: UUID
    sourceText: str = Field(min_length=1, max_length=100000)


class Rule(BaseModel):
    model_config = ConfigDict(extra="forbid")
    op: Literal["AND", "OR", "EQ", "IN", "BETWEEN"]
    field: Literal["LOT_NUMBER", "EXPIRY_DATE"] | None = None
    values: list[str] | None = None
    children: list["Rule"] | None = None


class ExtractionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    requestId: UUID
    sourceSha256: str
    schemaVersion: Literal["v1"] = "v1"
    mode: Literal["MOCK", "LIVE"]
    provider: str
    model: str
    promptVersion: str
    rule: Rule
    sourceQuote: str
    warnings: list[str]


class Provider(Protocol):
    async def extract(self, request: ExtractionRequest) -> ExtractionResponse: ...


class FixtureProvider:
    async def extract(self, request: ExtractionRequest) -> ExtractionResponse:
        fixture = json.loads((Path(__file__).parent / "fixtures/recall-001.json").read_text())
        if request.sourceText != fixture["sourceText"]:
            raise HTTPException(422, detail={"code": "UNSUPPORTED_FIXTURE", "message": "Only the exact documented synthetic notice is supported"})
        return ExtractionResponse(
            requestId=request.requestId,
            sourceSha256=hashlib.sha256(request.sourceText.encode()).hexdigest(),
            mode="MOCK", provider="fixture", model="fixture-v1", promptVersion="fixture-extract-v1",
            rule=Rule.model_validate(fixture["rule"]), sourceQuote=fixture["sourceQuote"],
            warnings=["개발용 고정 응답입니다. AI 모델 추출 결과가 아닙니다.", "상품 연결과 조건을 담당자가 검토해야 합니다."],
        )


def provider() -> Provider:
    mode = os.environ.get("WECALL_AI_PROVIDER", "disabled")
    if mode == "ollama-local":
        from app.local_provider import LocalProvider
        return LocalProvider()
    if mode == "fixture":
        return FixtureProvider()
    raise HTTPException(503, detail={"code": "MODEL_NOT_CONFIGURED", "message": "No model provider configured"})


@router.post("/v1/extractions", response_model=ExtractionResponse, tags=["extraction"])
async def extract(request: ExtractionRequest, x_service_token: str | None = Header(default=None)):
    expected = os.environ.get("WECALL_AI_SERVICE_TOKEN", "")
    if not expected:
        raise HTTPException(503, detail={"code": "SERVICE_NOT_CONFIGURED"})
    if not x_service_token or not hmac.compare_digest(x_service_token.encode(), expected.encode()):
        raise HTTPException(401, detail={"code": "UNAUTHORIZED_SERVICE"})
    if not request.sourceText.strip():
        raise HTTPException(422, detail={"code": "EMPTY_SOURCE"})
    return await provider().extract(request)
