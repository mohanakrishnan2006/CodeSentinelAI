from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel

from app.core.config import Settings
from app.services.ollama_service import (
    OllamaModelUnavailable,
    OllamaService,
    OllamaUnavailable,
)

router = APIRouter(prefix="/health", tags=["health"])


class HealthResponse(BaseModel):
    service: str
    status: Literal["ok"]


class OllamaHealthResponse(BaseModel):
    status: Literal["ok"]
    model_available: Literal[True]


def get_ollama_service(request: Request) -> OllamaService:
    settings: Settings = request.app.state.settings
    return OllamaService(settings)


@router.get("", response_model=HealthResponse)
async def health(request: Request) -> HealthResponse:
    settings: Settings = request.app.state.settings
    return HealthResponse(service=settings.app_name, status="ok")


@router.get("/ollama", response_model=OllamaHealthResponse)
async def ollama_health(
    ollama_service: OllamaService = Depends(get_ollama_service),
) -> OllamaHealthResponse:
    try:
        await ollama_service.check_model_available()
    except OllamaModelUnavailable as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Configured Ollama model is not available",
        ) from exc
    except OllamaUnavailable as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Ollama service is unavailable",
        ) from exc

    return OllamaHealthResponse(status="ok", model_available=True)
