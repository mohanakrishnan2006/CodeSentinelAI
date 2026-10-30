from functools import lru_cache
from urllib.parse import urlsplit

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "CodeSentinel AI Service"
    app_env: str = "development"
    host: str = "127.0.0.1"
    port: int = Field(default=8001, ge=1, le=65535)
    ollama_base_url: str = "http://localhost:11434"
    ollama_model: str = "qwen2.5-coder:3b"
    ollama_timeout_seconds: float = Field(default=30.0, gt=0, le=300)

    @field_validator("ollama_base_url")
    @classmethod
    def validate_ollama_base_url(cls, value: str) -> str:
        normalized = value.rstrip("/")
        try:
            parsed = urlsplit(normalized)
            valid_port = parsed.port is None or 1 <= parsed.port <= 65535
        except ValueError as exc:
            raise ValueError("OLLAMA_BASE_URL must be a valid HTTP URL") from exc
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
            or not valid_port
            or any(character.isspace() for character in normalized)
        ):
            raise ValueError("OLLAMA_BASE_URL must use HTTP or HTTPS")
        return normalized


@lru_cache
def get_settings() -> Settings:
    return Settings()
