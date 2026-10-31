from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.api.routes.health import get_ollama_service
from app.api.schemas.analysis import AnalysisRequest, AnalysisResponse
from app.services.analysis_service import (
    AnalysisService,
    UnsafeAnalysisResponse,
    UnsafeFindingContent,
)
from app.services.ollama_service import (
    OllamaGenerationTimedOut,
    OllamaGenerationUnavailable,
    OllamaInvalidGenerationResponse,
    OllamaService,
)
from app.services.secret_detection import SecretDetectionFailure

router = APIRouter(prefix="/api/v1", tags=["analysis"])
# 256 KiB accommodates the request fields' maximum JSON-escaped Unicode payload.
MAX_ANALYSIS_BODY_BYTES = 256 * 1024


def get_analysis_service(
    ollama_service: OllamaService = Depends(get_ollama_service),
) -> AnalysisService:
    return AnalysisService(ollama_service)


@router.post("/analyze", response_model=AnalysisResponse)
async def analyze(
    finding: AnalysisRequest,
    analysis_service: AnalysisService = Depends(get_analysis_service),
) -> AnalysisResponse:
    try:
        return await analysis_service.analyze(finding)
    except UnsafeFindingContent as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Finding contains content that cannot be analyzed safely",
        ) from exc
    except UnsafeAnalysisResponse as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Analysis response failed safety validation",
        ) from exc
    except SecretDetectionFailure as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Secret safety check is unavailable",
        ) from exc
    except OllamaGenerationTimedOut as exc:
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail="Analysis generation timed out",
        ) from exc
    except OllamaGenerationUnavailable as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Analysis service is unavailable",
        ) from exc
    except OllamaInvalidGenerationResponse as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Analysis service returned an invalid response",
        ) from exc
