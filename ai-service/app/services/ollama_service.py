import json
from typing import Any

import httpx
from pydantic import ValidationError

from app.core.config import Settings
from app.api.schemas.analysis import AnalysisResponse


class OllamaUnavailable(Exception):
    """Raised when Ollama cannot be reached or returns an invalid response."""


class OllamaModelUnavailable(Exception):
    """Raised when Ollama responds but does not have the configured model."""


class OllamaGenerationUnavailable(Exception):
    """Raised when Ollama cannot complete a generation request."""


class OllamaGenerationTimedOut(Exception):
    """Raised when an Ollama generation request times out."""


class OllamaInvalidGenerationResponse(Exception):
    """Raised when generated output does not match the required response schema."""


class OllamaService:
    MAX_GENERATION_RESPONSE_BYTES = 32_768

    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._settings = settings
        self._transport = transport

    async def generate_analysis(self, prompt: str, system_prompt: str) -> AnalysisResponse:
        timeout = httpx.Timeout(
            self._settings.ollama_generation_timeout_seconds,
            connect=min(5.0, self._settings.ollama_generation_timeout_seconds),
        )
        request_payload = {
            "model": self._settings.ollama_model,
            "system": system_prompt,
            "prompt": prompt,
            "stream": False,
            "format": AnalysisResponse.model_json_schema(by_alias=True),
            "options": {
                "temperature": 0.1,
                "num_predict": self._settings.ollama_analysis_num_predict,
            },
        }
        try:
            async with httpx.AsyncClient(
                base_url=self._settings.ollama_base_url,
                timeout=timeout,
                transport=self._transport,
            ) as client:
                async with client.stream(
                    "POST",
                    "/api/generate",
                    json=request_payload,
                ) as response:
                    response.raise_for_status()
                    response_body = bytearray()
                    async for chunk in response.aiter_bytes():
                        response_body.extend(chunk)
                        if len(response_body) > self.MAX_GENERATION_RESPONSE_BYTES:
                            raise OllamaInvalidGenerationResponse
        except httpx.TimeoutException as exc:
            raise OllamaGenerationTimedOut from exc
        except httpx.HTTPError as exc:
            raise OllamaGenerationUnavailable from exc

        try:
            payload: Any = json.loads(response_body)
        except (UnicodeDecodeError, ValueError) as exc:
            raise OllamaInvalidGenerationResponse from exc
        if not isinstance(payload, dict) or not isinstance(payload.get("response"), str):
            raise OllamaInvalidGenerationResponse
        try:
            return AnalysisResponse.model_validate_json(payload["response"])
        except (ValidationError, ValueError) as exc:
            raise OllamaInvalidGenerationResponse from exc

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
