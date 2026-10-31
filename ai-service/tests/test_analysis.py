import asyncio
import json

import httpx
import pytest
from fastapi import FastAPI

import app.services.analysis_service as analysis_service_module
from app.api.routes.analysis import get_analysis_service
from app.api.routes.analysis import MAX_ANALYSIS_BODY_BYTES
from app.api.schemas.analysis import AnalysisResponse
from app.core.config import Settings
from app.main import create_app
from app.services.analysis_service import AnalysisService
from app.services.ollama_service import OllamaService
from app.services.secret_detection import SecretDetectionFailure

ANALYSIS_URL = "/api/v1/analyze"
VALID_FINDING = {
    "ruleId": "java.lang.security.audit.xss",
    "severity": "HIGH",
    "message": "Untrusted input is written to an HTML response.",
    "filePath": "src/main/java/example/Controller.java",
    "startLine": 42,
    "startColumn": 9,
    "endLine": 42,
    "endColumn": 28,
    "scanner": "SEMGREP",
    "metadata": {},
}
VALID_ANALYSIS = {
    "explanation": "The application places untrusted data into HTML.",
    "why_it_matters": "An attacker may make a user's browser run unwanted script.",
    "recommended_remediation": "Encode the value for the HTML context before writing it.",
    "illustrative_fix": {
        "language": "java",
        "code": "response.getWriter().write(escapeHtml(userInput));",
    },
}


def post_analysis(
    app,
    payload: dict,
    headers: dict[str, str] | None = None,
) -> httpx.Response:
    async def request() -> httpx.Response:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://testserver",
        ) as client:
            return await client.post(ANALYSIS_URL, json=payload, headers=headers)

    return asyncio.run(request())


def post_analysis_asgi(
    app: FastAPI,
    body: bytes,
    headers: dict[bytes, bytes] | None = None,
    chunk_size: int = 8192,
) -> tuple[int, bytes, int]:
    async def request() -> tuple[int, bytes, int]:
        header_items = [(b"content-type", b"application/json")]
        if headers:
            header_items.extend(headers.items())
        chunks = [
            body[offset : offset + chunk_size]
            for offset in range(0, len(body), chunk_size)
        ] or [b""]
        receive_count = 0

        async def receive() -> dict:
            nonlocal receive_count
            if receive_count < len(chunks):
                index = receive_count
                receive_count += 1
                return {
                    "type": "http.request",
                    "body": chunks[index],
                    "more_body": index < len(chunks) - 1,
                }
            return {"type": "http.disconnect"}

        sent: list[dict] = []

        async def send(message: dict) -> None:
            sent.append(message)

        scope = {
            "type": "http",
            "asgi": {"version": "3.0", "spec_version": "2.3"},
            "http_version": "1.1",
            "method": "POST",
            "scheme": "http",
            "path": ANALYSIS_URL,
            "raw_path": ANALYSIS_URL.encode("ascii"),
            "query_string": b"",
            "root_path": "",
            "headers": header_items,
            "client": ("127.0.0.1", 54321),
            "server": ("testserver", 80),
            "state": {},
        }
        await app(scope, receive, send)
        status_code = next(
            message["status"]
            for message in sent
            if message["type"] == "http.response.start"
        )
        response_body = b"".join(
            message.get("body", b"")
            for message in sent
            if message["type"] == "http.response.body"
        )
        return status_code, response_body, receive_count

    return asyncio.run(request())


def app_with_transport(transport: httpx.AsyncBaseTransport) -> FastAPI:
    settings = Settings(
        ollama_base_url="http://ollama.test:11434",
        ollama_model="trusted-model",
        ollama_generation_timeout_seconds=45,
        ollama_analysis_num_predict=384,
    )
    app = create_app(settings)
    app.dependency_overrides[get_analysis_service] = lambda: AnalysisService(
        OllamaService(settings, transport=transport)
    )
    return app


def test_analysis_sends_structured_request_to_configured_ollama_and_returns_schema() -> None:
    captured: list[httpx.Request] = []

    def respond(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={"response": json.dumps(VALID_ANALYSIS)},
        )

    response = post_analysis(
        app_with_transport(httpx.MockTransport(respond)),
        VALID_FINDING,
    )

    assert response.status_code == 200
    assert response.json() == {
        "explanation": VALID_ANALYSIS["explanation"],
        "whyItMatters": VALID_ANALYSIS["why_it_matters"],
        "recommendedRemediation": VALID_ANALYSIS["recommended_remediation"],
        "illustrativeFix": VALID_ANALYSIS["illustrative_fix"],
    }
    assert len(captured) == 1
    assert captured[0].url == "http://ollama.test:11434/api/generate"
    assert captured[0].extensions["timeout"]["connect"] == 5.0
    assert captured[0].extensions["timeout"]["read"] == 45.0
    payload = json.loads(captured[0].content)
    assert payload["model"] == "trusted-model"
    assert payload["stream"] is False
    assert payload["options"]["num_predict"] == 384
    assert payload["format"]["type"] == "object"


def test_java_xss_prompt_and_response_use_contextual_owasp_encoder_example() -> None:
    captured: list[httpx.Request] = []
    java_xss_response = {
        "explanation": "Untrusted input is written into HTML text.",
        "why_it_matters": "A browser may interpret attacker-controlled markup as active content.",
        "recommended_remediation": (
            "Encode for HTML text context. Add the OWASP Java Encoder dependency; "
            "it is not part of the Java standard library."
        ),
        "illustrative_fix": {
            "language": "java",
            "code": "response.getWriter().write(org.owasp.encoder.Encode.forHtml(userInput));",
        },
    }

    def respond(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"response": json.dumps(java_xss_response)})

    response = post_analysis(
        app_with_transport(httpx.MockTransport(respond)),
        VALID_FINDING,
    )

    assert response.status_code == 200
    result = response.json()
    assert "org.owasp.encoder.Encode.forHtml(userInput)" in result["illustrativeFix"]["code"]
    assert "HTML text context" in result["recommendedRemediation"]
    assert "OWASP Java Encoder dependency" in result["recommendedRemediation"]

    request_payload = json.loads(captured[0].content)
    prompt = " ".join(request_payload["system"].split())
    assert "Never invent package names" in prompt
    assert "preserve the language" in prompt
    assert "org.owasp.encoder.Encode.forHtml(userInput)" in prompt
    assert "HTML text context only" in prompt
    assert "Never substitute invented APIs such as java.util.html.EscapeUtils" in prompt


def test_invented_java_util_html_api_is_not_returned_as_a_fix() -> None:
    generated = {
        **VALID_ANALYSIS,
        "illustrative_fix": {
            "language": "java",
            "code": (
                "import java.util.html.EscapeUtils;\n"
                "response.getWriter().write(EscapeUtils.escapeHtml4(userInput));"
            ),
        },
    }
    app = app_with_transport(
        httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                json={"response": json.dumps(generated)},
            )
        )
    )

    response = post_analysis(app, VALID_FINDING)

    assert response.status_code == 200
    assert response.json()["illustrativeFix"] is None
    assert "java.util.html.EscapeUtils" not in response.text


def test_analysis_returns_no_fix_when_model_is_not_confident() -> None:
    generated = {
        **VALID_ANALYSIS,
        "recommended_remediation": (
            "Use the language's established context-specific escaping API and verify "
            "it for the framework in use."
        ),
        "illustrative_fix": None,
    }
    captured: list[httpx.Request] = []

    def respond(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"response": json.dumps(generated)})

    response = post_analysis(app_with_transport(httpx.MockTransport(respond)), VALID_FINDING)

    assert response.status_code == 200
    assert response.json()["illustrativeFix"] is None
    assert "cannot confidently provide a valid" in json.loads(
        captured[0].content
    )["system"]


def test_secret_in_finding_message_rejects_before_ollama_call() -> None:
    requests: list[httpx.Request] = []
    secret = "DUMMY_UNRECOGNIZED_CREDENTIAL_7788"

    def respond(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"response": json.dumps(VALID_ANALYSIS)})

    finding = {
        **VALID_FINDING,
        "message": f'Client setting: client_secret = "{secret}"',
    }
    response = post_analysis(
        app_with_transport(httpx.MockTransport(respond)),
        finding,
    )

    assert response.status_code == 422
    assert secret not in response.text
    assert requests == []


def test_secret_in_snippet_rejects_before_ollama_call() -> None:
    requests: list[httpx.Request] = []
    secret = "DUMMY_SNIPPET_CREDENTIAL_6655"

    def respond(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"response": json.dumps(VALID_ANALYSIS)})

    finding = {
        **VALID_FINDING,
        "snippet": f'const api_key = "{secret}";',
    }
    response = post_analysis(
        app_with_transport(httpx.MockTransport(respond)),
        finding,
    )

    assert response.status_code == 422
    assert secret not in response.text
    assert requests == []


def test_secret_scanner_failure_rejects_before_ollama_call(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    requests: list[httpx.Request] = []

    def fail_scan(value: str) -> bool:
        raise SecretDetectionFailure

    monkeypatch.setattr(analysis_service_module, "contains_potential_secret", fail_scan)
    app = app_with_transport(
        httpx.MockTransport(
            lambda request: requests.append(request)
            or httpx.Response(200, json={"response": json.dumps(VALID_ANALYSIS)})
        )
    )

    response = post_analysis(app, VALID_FINDING)

    assert response.status_code == 503
    assert response.json() == {"detail": "Secret safety check is unavailable"}
    assert requests == []


def test_model_echoing_potential_secret_is_not_returned() -> None:
    secret = "DUMMY_MODEL_ECHO_CREDENTIAL_3311"
    generated = {
        **VALID_ANALYSIS,
        "explanation": f'Client setting: client_secret = "{secret}"',
    }

    response = post_analysis(
        app_with_transport(
            httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    json={"response": json.dumps(generated)},
                )
            )
        ),
        VALID_FINDING,
    )

    assert response.status_code == 502
    assert response.json() == {
        "detail": "Analysis response failed safety validation"
    }
    assert secret not in response.text


def test_normal_finding_text_passes_secret_scan_and_is_analyzed() -> None:
    captured: list[httpx.Request] = []

    def respond(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"response": json.dumps(VALID_ANALYSIS)})

    finding = {
        **VALID_FINDING,
        "message": "Untrusted input is written to an HTML response.",
        "snippet": "response.getWriter().write(userInput);",
    }
    response = post_analysis(
        app_with_transport(httpx.MockTransport(respond)),
        finding,
    )

    assert response.status_code == 200
    assert len(captured) == 1
    prompt = json.loads(captured[0].content)["prompt"]
    assert finding["message"] in prompt
    assert finding["snippet"] in prompt


def test_illustrative_fix_language_must_match_file_extension() -> None:
    generated = {
        **VALID_ANALYSIS,
        "illustrative_fix": {
            "language": "python",
            "code": "html.escape(user_input)",
        },
    }
    response = post_analysis(
        app_with_transport(
            httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    json={"response": json.dumps(generated)},
                )
            )
        ),
        VALID_FINDING,
    )

    assert response.status_code == 200
    assert response.json()["illustrativeFix"] is None


def test_illustrative_fix_is_omitted_for_unknown_file_extension() -> None:
    finding = {**VALID_FINDING, "filePath": "src/example.unknown"}
    response = post_analysis(
        app_with_transport(
            httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    json={"response": json.dumps(VALID_ANALYSIS)},
                )
            )
        ),
        finding,
    )

    assert response.status_code == 200
    assert response.json()["illustrativeFix"] is None


def test_gitleaks_message_metadata_and_snippet_are_never_sent_to_ollama() -> None:
    secret = "ghp_DUMMYTESTTOKEN012345678901234567890"
    captured: list[httpx.Request] = []

    def respond(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"response": json.dumps(VALID_ANALYSIS)})

    finding = {
        **VALID_FINDING,
        "scanner": "GITLEAKS",
        "ruleId": "generic-api-key",
        "message": f"Matched value: {secret}",
        "metadata": {"secret": secret},
        "snippet": f'const token = "{secret}";',
    }

    response = post_analysis(
        app_with_transport(httpx.MockTransport(respond)),
        finding,
    )

    outgoing = captured[0].content.decode("utf-8")
    assert response.status_code == 200
    assert secret not in outgoing
    assert "Matched value" not in outgoing
    assert "generic-api-key" not in outgoing


def test_analysis_rejects_detected_secret_in_input_before_ollama_call() -> None:
    secret = "DUMMY_ANALYSIS_SECRET_987654"
    generated = {
        **VALID_ANALYSIS,
        "explanation": f"Never reuse this password={secret}.",
    }
    captured: list[httpx.Request] = []

    def respond(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"response": json.dumps(generated)})

    finding = {
        **VALID_FINDING,
        "message": f"Unsafe usage; password={secret}",
        "snippet": f'password = "{secret}"',
    }

    response = post_analysis(
        app_with_transport(httpx.MockTransport(respond)),
        finding,
    )

    assert response.status_code == 422
    assert captured == []
    assert secret not in response.text


def test_analysis_validation_error_does_not_echo_untrusted_request_values() -> None:
    secret = "DUMMY_INVALID_REQUEST_SECRET"
    finding = {**VALID_FINDING, "severity": secret}
    response = post_analysis(
        app_with_transport(httpx.MockTransport(lambda request: httpx.Response(500))),
        finding,
    )

    assert response.status_code == 422
    assert secret not in response.text
    assert response.json() == {"detail": "Invalid request"}


def test_analysis_rejects_oversized_request_body_before_parsing() -> None:
    app = app_with_transport(
        httpx.MockTransport(lambda request: httpx.Response(500))
    )
    body = b"x" * (MAX_ANALYSIS_BODY_BYTES + 1)

    status_code, response_body, receive_count = post_analysis_asgi(
        app,
        body,
        headers={b"content-length": str(len(body)).encode("ascii")},
    )

    assert status_code == 413
    assert json.loads(response_body) == {"detail": "Analysis request is too large"}
    assert receive_count == (MAX_ANALYSIS_BODY_BYTES // 8192) + 1


def test_body_limit_rejects_oversized_body_without_content_length() -> None:
    app = app_with_transport(
        httpx.MockTransport(lambda request: httpx.Response(500))
    )
    body = b"x" * (MAX_ANALYSIS_BODY_BYTES + 1)

    status_code, response_body, receive_count = post_analysis_asgi(app, body)

    assert status_code == 413
    assert json.loads(response_body) == {"detail": "Analysis request is too large"}
    assert receive_count <= (MAX_ANALYSIS_BODY_BYTES // 8192) + 2


def test_body_limit_rejects_actual_oversize_despite_smaller_content_length() -> None:
    app = app_with_transport(
        httpx.MockTransport(lambda request: httpx.Response(500))
    )
    body = b"x" * (MAX_ANALYSIS_BODY_BYTES + 1)

    status_code, response_body, _ = post_analysis_asgi(
        app,
        body,
        headers={b"content-length": b"1"},
    )

    assert status_code == 413
    assert json.loads(response_body) == {"detail": "Analysis request is too large"}


def test_body_limit_preserves_valid_request_below_limit() -> None:
    captured: list[httpx.Request] = []

    def respond(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"response": json.dumps(VALID_ANALYSIS)})

    body = json.dumps(VALID_FINDING).encode("utf-8")
    status_code, response_body, _ = post_analysis_asgi(
        app_with_transport(httpx.MockTransport(respond)),
        body,
    )

    assert status_code == 200
    assert json.loads(response_body)["explanation"] == VALID_ANALYSIS["explanation"]
    assert len(captured) == 1


def test_body_limit_accepts_largest_schema_valid_request() -> None:
    captured: list[httpx.Request] = []

    def respond(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"response": json.dumps(VALID_ANALYSIS)})

    maximum_metadata = {
        f"{'😀' * 124}{index:04d}": "😀" * 512
        for index in range(16)
    }
    maximum_finding = {
        **VALID_FINDING,
        "ruleId": "😀" * 2048,
        "message": "😀" * 2048,
        "filePath": "a/" * 511 + "a",
        "snippet": "😀" * 4096,
        "metadata": maximum_metadata,
    }
    body = json.dumps(maximum_finding, ensure_ascii=True).encode("ascii")
    assert len(body) > 16_384
    assert len(body) <= MAX_ANALYSIS_BODY_BYTES

    status_code, response_body, _ = post_analysis_asgi(
        app_with_transport(httpx.MockTransport(respond)),
        body,
        headers={b"content-length": str(len(body)).encode("ascii")},
        chunk_size=4096,
    )

    assert status_code == 200
    assert json.loads(response_body)["explanation"] == VALID_ANALYSIS["explanation"]
    assert len(captured) == 1


def test_analysis_maps_ollama_timeout_and_hides_upstream_details() -> None:
    def timeout(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("private timeout and request details", request=request)

    response = post_analysis(app_with_transport(httpx.MockTransport(timeout)), VALID_FINDING)

    assert response.status_code == 504
    assert response.json() == {"detail": "Analysis generation timed out"}
    assert "private timeout" not in response.text
    assert "Controller.java" not in response.text


def test_analysis_maps_ollama_connection_failure_to_controlled_unavailable() -> None:
    def fail(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("private Ollama host details", request=request)

    response = post_analysis(app_with_transport(httpx.MockTransport(fail)), VALID_FINDING)

    assert response.status_code == 503
    assert response.json() == {"detail": "Analysis service is unavailable"}
    assert "private Ollama" not in response.text


def test_analysis_rejects_malformed_or_schema_invalid_ollama_output() -> None:
    invalid_outputs = [
        "not JSON",
        json.dumps({**VALID_ANALYSIS, "extra": "not allowed"}),
        json.dumps({**VALID_ANALYSIS, "explanation": ""}),
    ]

    for generated_output in invalid_outputs:
        app = app_with_transport(
            httpx.MockTransport(
                lambda request, output=generated_output: httpx.Response(
                    200,
                    json={"response": output},
                )
            )
        )
        response = post_analysis(app, VALID_FINDING)
        assert response.status_code == 502
        assert response.json() == {
            "detail": "Analysis service returned an invalid response"
        }


def test_analysis_rejects_oversized_ollama_response() -> None:
    app = app_with_transport(
        httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                content=b"x" * 32_769,
            )
        )
    )

    response = post_analysis(app, VALID_FINDING)

    assert response.status_code == 502
    assert response.json() == {
        "detail": "Analysis service returned an invalid response"
    }


def test_analysis_schema_rejects_path_traversal_and_invalid_coordinates() -> None:
    for invalid_values in (
        {"filePath": "../outside.java"},
        {"filePath": "src\\outside.java"},
        {"startLine": 43, "endLine": 42},
    ):
        response = post_analysis(
            app_with_transport(httpx.MockTransport(lambda request: httpx.Response(500))),
            {**VALID_FINDING, **invalid_values},
        )
        assert response.status_code == 422


def test_analysis_rejects_caller_supplied_ollama_configuration() -> None:
    response = post_analysis(
        app_with_transport(httpx.MockTransport(lambda request: httpx.Response(500))),
        {**VALID_FINDING, "ollamaBaseUrl": "http://attacker.invalid"},
    )

    assert response.status_code == 422
    assert "attacker.invalid" not in response.text


def test_analysis_response_schema_rejects_extra_fields() -> None:
    invalid = {**VALID_ANALYSIS, "rawPrompt": "must not pass"}
    with pytest.raises(ValueError):
        AnalysisResponse.model_validate(invalid)
