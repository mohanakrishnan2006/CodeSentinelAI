import asyncio
import httpx
import pytest

from app.core.config import Settings
from app.services.ollama_service import (
    OllamaModelUnavailable,
    OllamaService,
    OllamaUnavailable,
)


def test_check_model_available_requests_tags_with_configured_timeout() -> None:
    settings = Settings(ollama_model="qwen2.5-coder:3b", ollama_timeout_seconds=12)
    requests: list[httpx.Request] = []

    def respond(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"models": [{"name": "qwen2.5-coder:3b"}]})

    service = OllamaService(settings, transport=httpx.MockTransport(respond))

    asyncio.run(service.check_model_available())

    assert len(requests) == 1
    assert requests[0].url.path == "/api/tags"
    assert requests[0].url.host == "localhost"
    assert requests[0].extensions["timeout"]["connect"] == 5.0
    assert requests[0].extensions["timeout"]["read"] == 12.0


def test_check_model_available_rejects_missing_model() -> None:
    service = OllamaService(
        Settings(ollama_model="qwen2.5-coder:3b"),
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json={"models": [{"name": "other-model"}]})
        ),
    )

    with pytest.raises(OllamaModelUnavailable):
        asyncio.run(service.check_model_available())


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(503, json={"error": "private upstream detail"}),
        httpx.Response(200, text="not JSON"),
        httpx.Response(200, json={"unexpected": []}),
    ],
)
def test_check_model_available_rejects_unavailable_or_invalid_responses(
    response: httpx.Response,
) -> None:
    service = OllamaService(
        Settings(),
        transport=httpx.MockTransport(lambda request: response),
    )

    with pytest.raises(OllamaUnavailable):
        asyncio.run(service.check_model_available())


def test_settings_read_configuration_from_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("APP_NAME", "Configured AI Service")
    monkeypatch.setenv("PORT", "8123")
    monkeypatch.setenv("OLLAMA_MODEL", "qwen2.5-coder:3b")

    settings = Settings(_env_file=None)

    assert settings.app_name == "Configured AI Service"
    assert settings.port == 8123
    assert settings.ollama_model == "qwen2.5-coder:3b"


def test_settings_reject_credentials_in_ollama_url() -> None:
    with pytest.raises(ValueError):
        Settings(ollama_base_url="http://user:password@localhost:11434")
