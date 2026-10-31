# CodeSentinel AI Service

The AI service is a separate FastAPI application. It provides health checks and an analysis endpoint that uses the configured local Ollama model to explain normalized static-analysis findings. It does not execute submitted or generated code.

## Requirements

- Python 3.12
- Ollama is optional for running the automated tests.
- For the Ollama health check, run Ollama locally and separately pull the configured model. The service never downloads models automatically.

## Setup on Windows PowerShell

From the repository root:

```powershell
py -3.12 -m venv ai-service\.venv
.\ai-service\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r ai-service\requirements.txt
Copy-Item ai-service\.env.example ai-service\.env
```

Edit `ai-service\.env` only if you need different local settings. Do not put credentials in this file or commit it.

## Run tests

```powershell
Set-Location ai-service
python -m pytest
```

The tests use mocked HTTPX transports; they do not contact Ollama or the internet.

## Start the service

From the repository root:

```powershell
Set-Location ai-service
python -m app.main
```

The service uses the configured `HOST` and `PORT` values when started this way. For Uvicorn reload mode during development, use:

```powershell
python -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8001
```

Configuration can be supplied through environment variables or `ai-service\.env`:

| Variable | Default |
|---|---|
| `APP_NAME` | `CodeSentinel AI Service` |
| `APP_ENV` | `development` |
| `HOST` | `127.0.0.1` |
| `PORT` | `8001` |
| `OLLAMA_BASE_URL` | `http://localhost:11434` |
| `OLLAMA_MODEL` | `qwen2.5-coder:3b` |
| `OLLAMA_TIMEOUT_SECONDS` | `30` |
| `OLLAMA_GENERATION_TIMEOUT_SECONDS` | `120` |
| `OLLAMA_ANALYSIS_NUM_PREDICT` | `512` |

The `python -m app.main` entry point reads `HOST` and `PORT` from settings. If using the Uvicorn CLI instead, pass `--host` and `--port` explicitly.

Health endpoints:

- `GET http://127.0.0.1:8001/health`
- `GET http://127.0.0.1:8001/health/ollama`
- `POST http://127.0.0.1:8001/api/v1/analyze`

The Ollama check calls `/api/tags`. It returns `503 Service Unavailable` with a controlled message when Ollama cannot be reached, returns an invalid response, or does not have the configured model.

### Analyze a finding

The request uses the Java backend's normalized finding field names. A bounded optional `snippet` may be included for non-Gitleaks findings; metadata is accepted for compatibility but is never sent to Ollama. Gitleaks requests are reduced to fixed, generic context, and their message and snippet are not sent.

```powershell
$finding = @{
    ruleId = "java.lang.security.audit.xss"
    severity = "HIGH"
    message = "Untrusted input is written to an HTML response."
    filePath = "src/main/java/example/Controller.java"
    startLine = 42
    startColumn = 9
    endLine = 42
    endColumn = 28
    scanner = "SEMGREP"
    metadata = @{}
    snippet = 'response.getWriter().write(userInput);'
} | ConvertTo-Json

Invoke-RestMethod `
    -Uri "http://127.0.0.1:8001/api/v1/analyze" `
    -Method Post `
    -ContentType "application/json" `
    -Body $finding
```

The response contains `explanation`, `whyItMatters`, `recommendedRemediation`, and nullable `illustrativeFix` (`language` and `code`). The generated content is advisory and is neither executed nor persisted. The ASGI layer limits the actual received request body to 256 KiB regardless of `Content-Length`; the limit accommodates the request schema's maximum field sizes. Snippets are limited to 4 KiB, and generated output uses the configured token and timeout bounds.

### Input and output secret checks

Before Ollama is called, the service scans the free-text rule ID, finding message, and optional snippet with the maintained `detect-secrets` library. If a potential secret is detected, or scanning fails, analysis is rejected with a generic response and no Ollama request. Finding paths and metadata are not included in prompts. Gitleaks findings use only generic scanner context.

Generated explanation, remediation, and fix fields are also scanned; a response with a detected potential secret is rejected rather than returned. Fix language is matched against a supported file-extension mapping; unknown extensions or mismatches omit the illustrative fix.

Secret scanners use maintained detection rules and heuristics, but can miss novel, encoded, low-entropy, or otherwise unrecognized secrets and can flag benign text. Entropy-only Base64/hex plugins are disabled to avoid excessive false positives on source and prose. Passing a scan is not proof that text contains no secrets. Avoid submitting sensitive source text; this local safety layer is not a substitute for upstream secret scrubbing or access controls.
