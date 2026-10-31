import uvicorn
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.routes.analysis import MAX_ANALYSIS_BODY_BYTES, router as analysis_router
from app.api.routes.health import router as health_router
from app.core.config import Settings, get_settings
from app.middleware import AnalysisBodySizeLimitMiddleware


def create_app(settings: Settings | None = None) -> FastAPI:
    service_settings = settings or get_settings()
    application = FastAPI(title=service_settings.app_name)
    application.state.settings = service_settings
    application.include_router(health_router)
    application.include_router(analysis_router)
    application.add_middleware(
        AnalysisBodySizeLimitMiddleware,
        max_body_bytes=MAX_ANALYSIS_BODY_BYTES,
    )

    @application.exception_handler(RequestValidationError)
    async def safe_validation_error(
        request: Request,
        exc: RequestValidationError,
    ) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content={"detail": "Invalid request"},
        )

    return application


app = create_app()


if __name__ == "__main__":
    settings = app.state.settings
    uvicorn.run(app, host=settings.host, port=settings.port)
