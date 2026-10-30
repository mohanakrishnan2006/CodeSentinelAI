import uvicorn
from fastapi import FastAPI

from app.api.routes.health import router as health_router
from app.core.config import Settings, get_settings


def create_app(settings: Settings | None = None) -> FastAPI:
    service_settings = settings or get_settings()
    application = FastAPI(title=service_settings.app_name)
    application.state.settings = service_settings
    application.include_router(health_router)
    return application


app = create_app()


if __name__ == "__main__":
    settings = app.state.settings
    uvicorn.run(app, host=settings.host, port=settings.port)
