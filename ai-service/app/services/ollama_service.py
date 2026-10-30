from typing import Any

import httpx

from app.core.config import Settings


class OllamaUnavailable(Exception):
    """Raised when Ollama cannot be reached or returns an invalid response."""


class OllamaModelUnavailable(Exception):
    """Raised when Ollama responds but does not have the configured model."""


class OllamaService:
    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._settings = settings
        self._transport = transport

    async def check_model_available(self) -> None:
        timeout = httpx.Timeout(
            self._settings.ollama_timeout_seconds,
            connect=min(5.0, self._settings.ollama_timeout_seconds),
        )
        try:
            async with httpx.AsyncClient(
                base_url=self._settings.ollama_base_url,
                timeout=timeout,
                transport=self._transport,
            ) as client:
                response = await client.get("/api/tags")
                response.raise_for_status()
                payload: Any = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise OllamaUnavailable from exc

        model_names = self._extract_model_names(payload)
        if model_names is None:
            raise OllamaUnavailable
        if self._settings.ollama_model not in model_names:
            raise OllamaModelUnavailable

    @staticmethod
    def _extract_model_names(payload: Any) -> list[str] | None:
        if not isinstance(payload, dict):
            return None
        models = payload.get("models")
        if not isinstance(models, list):
            return None

        names: list[str] = []
        for model in models:
            if not isinstance(model, dict):
                return None
            name = model.get("name")
            if not isinstance(name, str) or not name:
                return None
            names.append(name)
        return names
