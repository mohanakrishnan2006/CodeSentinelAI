import asyncio

import httpx

from app.api.routes.health import get_ollama_service
from app.core.config import Settings
from app.main import create_app
from app.services.ollama_service import OllamaService


def get(app, path: str) -> httpx.Response:
    async def request() -> httpx.Response:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://testserver",
        ) as client:
            return await client.get(path)

    return asyncio.run(request())


def test_health_returns_service_name_and_status() -> None:
    app = create_app(Settings(app_name="Test AI Service"))

    response = get(app, "/health")

    assert response.status_code == 200
    assert response.json() == {"service": "Test AI Service", "status": "ok"}


def test_ollama_health_reports_configured_model_available() -> None:
    settings = Settings(ollama_model="qwen2.5-coder:3b")
    app = create_app(settings)
    transport = httpx.MockTransport(
        lambda request: httpx.Response(
            200,
            json={"models": [{"name": "qwen2.5-coder:3b"}]},
        )
    )
    app.dependency_overrides[get_ollama_service] = lambda: OllamaService(
        settings,
        transport=transport,
    )

    response = get(app, "/health/ollama")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "model_available": True}


def test_ollama_health_hides_connection_failure_details() -> None:
    settings = Settings(ollama_base_url="http://ollama.internal:11434")

    def fail_request(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("private host and connection details", request=request)

    app = create_app(settings)
    app.dependency_overrides[get_ollama_service] = lambda: OllamaService(
        settings,
        transport=httpx.MockTransport(fail_request),
    )

    response = get(app, "/health/ollama")

    assert response.status_code == 503
    assert response.json() == {"detail": "Ollama service is unavailable"}
    assert "private host" not in response.text
    assert "ollama.internal" not in response.text


def test_ollama_health_returns_unavailable_when_model_is_missing() -> None:
    settings = Settings(ollama_model="qwen2.5-coder:3b")
    app = create_app(settings)
    transport = httpx.MockTransport(
        lambda request: httpx.Response(200, json={"models": [{"name": "other-model:latest"}]})
    )
    app.dependency_overrides[get_ollama_service] = lambda: OllamaService(
        settings,
        transport=transport,
    )

    response = get(app, "/health/ollama")

    assert response.status_code == 503
    assert response.json() == {"detail": "Configured Ollama model is not available"}
    assert settings.ollama_model not in response.text
