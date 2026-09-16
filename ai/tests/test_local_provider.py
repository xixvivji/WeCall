import asyncio
import hashlib
import json
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from app.main import app
from app import local_provider as local
from app.extraction import ExtractionRequest

SOURCE = "합성 공문: 제조번호 A01 또는 A02이면서 소비기한 2026-10-31인 가상 과자 100g을 회수합니다."
RULE = {"op": "AND", "children": [
    {"op": "IN", "field": "LOT_NUMBER", "values": ["A01", "A02"]},
    {"op": "EQ", "field": "EXPIRY_DATE", "values": ["2026-10-31"]},
]}


@pytest.fixture
def model(monkeypatch):
    monkeypatch.setenv("WECALL_AI_SERVICE_TOKEN", "synthetic-test-token")
    monkeypatch.setenv("WECALL_AI_PROVIDER", "ollama-local")
    state = {"draft": {"status": "EXTRACTED", "join": "AND", "criteria": RULE["children"], "startLine": 1, "endLine": 1, "warnings": []},
             "info": {"details": {"format": "gguf"}, "model_info": {"test": True}}, "requests": []}
    original = httpx.AsyncClient
    def handler(request):
        state["requests"].append(request)
        assert str(request.url).startswith("http://127.0.0.1:11434/")
        if request.url.path == "/api/show":
            return httpx.Response(200, json=state["info"])
        return httpx.Response(state.get("http_status", 200), json={
            "model": local.MODEL, "done": True, "done_reason": state.get("reason", "stop"),
            "message": {"content": json.dumps(state["draft"])}},
            headers=state.get("headers", {}))
    def client(**kwargs):
        assert kwargs["trust_env"] is False and kwargs["follow_redirects"] is False
        return original(**kwargs, transport=httpx.MockTransport(handler))
    monkeypatch.setattr(local.httpx, "AsyncClient", client)
    return state


def send(source=SOURCE):
    with TestClient(app) as client:
        return client.post("/v1/extractions", json={"requestId": str(uuid4()), "sourceText": source},
                           headers={"X-Service-Token": "synthetic-test-token"})


def test_live_identity_and_transport_are_server_controlled(model):
    response = send()
    assert response.status_code == 200
    body = response.json()
    assert body["mode"] == "LIVE" and body["provider"] == "ollama-local"
    assert body["sourceSha256"] == hashlib.sha256(SOURCE.encode()).hexdigest()
    chat = json.loads(model["requests"][-1].content)
    assert chat["model"] == local.MODEL and chat["stream"] is False
    assert "tools" not in chat and "Authorization" not in model["requests"][-1].headers
    assert "sourceLines" in chat["messages"][1]["content"]


@pytest.mark.parametrize("change", ["quote", "lot", "date", "branch", "truncated", "oversized"])
def test_invalid_or_ungrounded_output_is_not_returned_as_draft(model, change):
    if change == "quote": model["draft"]["endLine"] = 2
    if change == "lot": model["draft"]["criteria"] = [{"op": "EQ", "field": "LOT_NUMBER", "values": ["A0"]}]
    if change == "date": model["draft"]["criteria"] = [{"op": "EQ", "field": "EXPIRY_DATE", "values": ["2026-02-30"]}]
    if change == "branch": model["draft"]["criteria"] = [{"op": "AND", "children": []}]
    if change == "truncated": model["reason"] = "length"
    if change == "oversized": model["draft"]["warnings"] = ["x" * 70000]
    response = send()
    assert response.status_code == 502
    assert SOURCE not in response.text


def test_refusal_does_not_invent_a_rule(model):
    model["draft"] = {"status": "NEEDS_REVIEW", "join": "AND", "criteria": [], "startLine": None, "endLine": None, "warnings": ["판단 불가"]}
    assert send().json()["detail"]["code"] == "MANUAL_REVIEW_REQUIRED"


def test_cloud_model_metadata_is_blocked_before_document_is_sent(model):
    model["info"]["remote_host"] = "https://example.test"
    assert send().status_code == 503
    assert len(model["requests"]) == 1
    assert SOURCE not in model["requests"][0].content.decode()


def test_redirect_is_not_followed(model):
    model["http_status"] = 307
    model["headers"] = {"Location": "https://example.test/collect"}
    assert send().status_code == 503
    assert len(model["requests"]) == 2


def test_oversize_source_is_rejected_without_silent_truncation(model):
    assert send("가" * 2001).status_code == 422
    assert model["requests"] == []


def test_deadline_releases_slot_and_errors_omit_document(model, monkeypatch):
    async def slow(*args):
        await asyncio.sleep(1)
    monkeypatch.setattr(local, "read_json", slow)
    monkeypatch.setattr(local, "DEADLINE_SECONDS", .01)
    assert send().status_code == 504
    assert not local._busy


def test_concurrent_request_has_no_queue(model, monkeypatch):
    async def exercise():
        entered = asyncio.Event()
        async def slow(*args):
            entered.set()
            await asyncio.sleep(1)
        monkeypatch.setattr(local, "read_json", slow)
        monkeypatch.setattr(local, "DEADLINE_SECONDS", .05)
        request = ExtractionRequest(requestId=uuid4(), sourceText=SOURCE)
        first = asyncio.create_task(local.LocalProvider().extract(request))
        await entered.wait()
        with pytest.raises(Exception) as error:
            await local.LocalProvider().extract(request)
        assert error.value.detail["code"] == "LOCAL_MODEL_BUSY"
        with pytest.raises(Exception):
            await first
        assert not local._busy
    asyncio.run(exercise())


def test_unsupported_exclusion_never_reaches_model(model):
    assert send("제조번호 X99를 제외한 제품 회수").json()["detail"]["code"] == "MANUAL_REVIEW_REQUIRED"
    assert model["requests"] == []


@pytest.mark.parametrize("metadata", [[], {"details": None}, {"details": {"format": "gguf"}}])
def test_malformed_metadata_fails_closed(model, metadata):
    model["info"] = metadata
    assert send().status_code in (502, 503)
    assert len(model["requests"]) == 1


def test_line_limit_prevents_context_truncation(model):
    assert send("x\n" * 81).status_code == 422
    assert model["requests"] == []
