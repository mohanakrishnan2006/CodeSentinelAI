import unicodedata
from enum import StrEnum
from typing import Annotated

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    field_validator,
    model_validator,
)


class FindingSeverity(StrEnum):
    CRITICAL = "CRITICAL"
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"
    INFO = "INFO"


class FindingScanner(StrEnum):
    SEMGREP = "SEMGREP"
    GITLEAKS = "GITLEAKS"
    TRIVY = "TRIVY"


SafeText = Annotated[
    str,
    StringConstraints(min_length=1, max_length=2048, strip_whitespace=True),
]
SafePath = Annotated[
    str,
    StringConstraints(min_length=1, max_length=1024, strip_whitespace=True),
]
Snippet = Annotated[str, StringConstraints(max_length=4096)]


class AnalysisRequest(BaseModel):
    model_config = ConfigDict(
        alias_generator=lambda value: "".join(
            part.capitalize() if index else part
            for index, part in enumerate(value.split("_"))
        ),
        populate_by_name=True,
        extra="forbid",
    )

    rule_id: SafeText = Field(alias="ruleId")
    severity: FindingSeverity
    message: SafeText
    file_path: SafePath = Field(alias="filePath")
    start_line: int | None = Field(default=None, ge=1, le=10_000_000, alias="startLine")
    start_column: int | None = Field(default=None, ge=1, le=10_000_000, alias="startColumn")
    end_line: int | None = Field(default=None, ge=1, le=10_000_000, alias="endLine")
    end_column: int | None = Field(default=None, ge=1, le=10_000_000, alias="endColumn")
    scanner: FindingScanner
    metadata: dict[str, str] = Field(default_factory=dict, max_length=16)
    snippet: Snippet | None = None

    @field_validator("rule_id", "message", "file_path")
    @classmethod
    def reject_control_characters(cls, value: str) -> str:
        if any(unicodedata.category(character) == "Cc" for character in value):
            raise ValueError("control characters are not permitted")
        return value

    @model_validator(mode="after")
    def validate_coordinates(self) -> "AnalysisRequest":
        if (self.start_line is None) != (self.end_line is None):
            raise ValueError("startLine and endLine must both be provided or omitted")
        if (self.start_column is None) != (self.end_column is None):
            raise ValueError("startColumn and endColumn must both be provided or omitted")
        if self.start_line is None and self.start_column is not None:
            raise ValueError("columns require line coordinates")
        if self.end_line is not None and self.start_line is not None:
            if self.end_line < self.start_line:
                raise ValueError("endLine must not precede startLine")
            if (
                self.end_line == self.start_line
                and self.start_column is not None
                and self.end_column is not None
                and self.end_column < self.start_column
            ):
                raise ValueError("endColumn must not precede startColumn")
        for key, value in self.metadata.items():
            if len(key) > 128 or len(value) > 512:
                raise ValueError("metadata field exceeds the permitted length")
        path_parts = self.file_path.split("/")
        if (
            self.file_path.startswith(("/", "\\"))
            or "\\" in self.file_path
            or ":" in self.file_path
            or any(part in {"", ".", ".."} for part in path_parts)
        ):
            raise ValueError("filePath must be a normalized relative path")
        return self


class IllustrativeFix(BaseModel):
    model_config = ConfigDict(
        alias_generator=lambda value: "".join(
            part.capitalize() if index else part
            for index, part in enumerate(value.split("_"))
        ),
        populate_by_name=True,
        extra="forbid",
    )

    language: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    code: Annotated[str, StringConstraints(min_length=1, max_length=4096)]


class AnalysisResponse(BaseModel):
    model_config = ConfigDict(
        alias_generator=lambda value: "".join(
            part.capitalize() if index else part
            for index, part in enumerate(value.split("_"))
        ),
        populate_by_name=True,
        extra="forbid",
    )

    explanation: Annotated[str, StringConstraints(min_length=1, max_length=3000)]
    why_it_matters: Annotated[str, StringConstraints(min_length=1, max_length=2000)]
    recommended_remediation: Annotated[
        str, StringConstraints(min_length=1, max_length=3000)
    ]
    illustrative_fix: IllustrativeFix | None = None
