# CodeSentinel AI Service

The AI service is a separate FastAPI application. This milestone provides basic service health checks and a safe connectivity/model-availability check for a local Ollama server. It does not analyze source code or generate fixes.

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

The `python -m app.main` entry point reads `HOST` and `PORT` from settings. If using the Uvicorn CLI instead, pass `--host` and `--port` explicitly.

Health endpoints:

- `GET http://127.0.0.1:8001/health`
- `GET http://127.0.0.1:8001/health/ollama`

The Ollama check calls `/api/tags`. It returns `503 Service Unavailable` with a controlled message when Ollama cannot be reached, returns an invalid response, or does not have the configured model.
