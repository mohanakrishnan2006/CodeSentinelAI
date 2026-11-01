# Development Roadmap — CodeSentinel AI

This document establishes the step-by-step implementation plan for CodeSentinel AI. Each phase is self-contained, tested, and verified before proceeding to the next.

---

## Phase Summary Matrix

| Phase | Description | Key Deliverables | Verification Milestone |
|:---:|---|---|---|
| **Phase 0** | **Architecture & Blueprint** | System architecture, ER diagrams, API specs, environment setup. | Workspace initialized, docs approved. |
| **Phase 1** | **Backend Foundation & Auth** | Spring Boot 3, PostgreSQL, Spring Security, JWT, User entity. | User registration/login tests passing, JWT validated. |
| **Phase 2** | **Project & Secure Ingestion** | Projects, Versions, ZIP file upload, `Zip Slip` prevention. | Upload sample ZIP, verify safe extraction and DB records. |
| **Phase 3** | **Static Security Scanners** | Scan management, isolated Semgrep/Gitleaks/Trivy integration, safe per-scanner outcomes, findings persistence, and owner-scoped findings APIs. | Verify scanner outcomes, persisted safe findings, ownership isolation, and Docker security controls. |
| **Phase 4** | **AI Microservice & Ollama** | Python 3.12 FastAPI foundation, health endpoints, and validated finding-analysis API backed by configured local Ollama. | Mocked tests cover health, analysis, validation, redaction, unavailable Ollama, malformed output, and limits without internet. |
| **Phase 5** | **RAG & Code Fixes** | LangChain, ChromaDB, code chunking, unified diffs, patch applier. | Context-aware patch generated and applied, creating `v2`. |
| **Phase 6** | **Frontend SPA** | React, Tailwind CSS, Monaco/Diff viewer, Scan Dashboard. | User can log in, upload code, view scan findings and diffs in UI. |
| **Phase 7** | **Docker Test Sandbox** | Isolated Docker containers (`--network none`, resource limits). | Untrusted tests run safely in sandbox; logs streamed to backend. |
| **Phase 8** | **PDF Reports & Reviews** | OpenPDF reports, reviewer invitations, line-specific comments. | Downloadable PDF generated; reviewer comments functional. |
| **Phase 9** | **End-to-End Validation** | Full integration testing, security audit, interview readiness. | Complete flow works seamlessly from upload to report. |

---

## Detailed Phase Breakdown

### Phase 0: System Architecture & Documentation Approval (Current)
- [x] Complete workspace and environment inspection.
- [x] Hardware capability analysis (Ryzen 5 7520U + 16GB RAM + Radeon 610M).
- [x] Compatibility resolution: Side-by-side Python 3.12 for ML libraries; lightweight `qwen2.5-coder:3b` model.
- [x] Author comprehensive [docs/PROJECT_OVERVIEW.md](file:///c:/Users/mohan/Documents/CodeSentinelAI/docs/PROJECT_OVERVIEW.md).
- [x] Author comprehensive [docs/ARCHITECTURE.md](file:///c:/Users/mohan/Documents/CodeSentinelAI/docs/ARCHITECTURE.md).
- [x] Author comprehensive [docs/DEVELOPMENT_ROADMAP.md](file:///c:/Users/mohan/Documents/CodeSentinelAI/docs/DEVELOPMENT_ROADMAP.md).
- [x] Update [README.md](file:///c:/Users/mohan/Documents/CodeSentinelAI/README.md).

---

### Phase 1: Backend Foundation, Authentication & Database Design
- **Objective**: Establish the core Java 21 Spring Boot application, connect to PostgreSQL, and implement stateless JWT authentication.
- **Key Deliverables**:
  - `pom.xml` with dependencies: Spring Boot Starter Web, Spring Security, Spring Data JPA, PostgreSQL driver, JJWT (io.jsonwebtoken), Lombok, Validation.
  - Database configuration in `application.yml` and `application-dev.yml`.
  - Database entities: `User`, `Role` (with UUID primary keys and timestamps).
  - Spring Security configuration with `SecurityFilterChain`, `JwtAuthenticationFilter`, and `BCryptPasswordEncoder`.
  - Auth Controller & DTOs: `RegisterRequest`, `LoginRequest`, `AuthResponse`, `UserResponse`.
  - Global exception handler (`@ControllerAdvice`) for input validation and security errors.
- **Verification Milestone**:
  - Automated JUnit tests for password hashing, user registration, invalid credentials rejection, and valid JWT issuance/verification.
  - Manual verification via curl/Postman hitting `/api/v1/auth/register` and `/api/v1/auth/login`.

---

### Phase 2: Project Management & Secure File Ingestion
- **Objective**: Allow users to create private projects, upload source code ZIP packages, safely extract them without path traversal risks, and establish `Version 1`.
- **Key Deliverables**:
  - Database entities: `Project` and `ProjectVersion`.
  - Storage service managing isolated directory trees in `storage/projects/<projectId>/v<versionNumber>/`.
  - Secure ZIP extraction utility enforcing canonical path verification (`Zip Slip` protection) and size limits.
  - Project CRUD endpoints (`GET /projects`, `POST /projects`, `DELETE /projects/{id}`).
  - Upload endpoint (`POST /projects/{id}/upload`) and file tree browsing endpoint (`GET /projects/{id}/versions/{versionId}/files`).
- **Verification Milestone**:
  - Test uploading a valid multi-file ZIP: verify directory structure unpacked and `ProjectVersion(versionNumber=1)` created in DB.
  - Test uploading a malicious ZIP containing `../` path traversal: verify extraction is aborted with security exception.

---

### Phase 3: Static Security Scanners Pipeline
- **Objective**: Manage scan requests against specific uploaded snapshots and run isolated Semgrep, Gitleaks, and Trivy analysis without persisting secret-bearing scanner output.
- **Implemented and tested**:
  - `ScanRun` records reference a completed `ProjectUpload` and begin in `QUEUED` status.
  - Authenticated, owner-scoped endpoints create scan requests, list an upload's scan history, and retrieve scan details.
  - Safe response metadata includes lifecycle timestamps and zero-initialized summary counters; raw scanner output and secrets are not stored.
  - An opt-in host-side worker polls PostgreSQL, claims queued work atomically, validates upload storage references, and recovers expired leases as failed.
  - The placeholder executor does not inspect or execute source code. Project deletion cancels queued scans and returns 409 while a scan is running; existing post-commit uploaded-file cleanup is preserved.
  - The composite worker executes Semgrep, then Gitleaks, then Trivy in restricted Docker containers with pinned images. Trivy uses a separately refreshed trusted host database cache mounted read-only; its container remains network-disabled. Uploaded source code is not executed.
  - Each scanner's outcome is persisted in `scan_outcomes` with status, deduplicated finding count, timestamps, and a controlled failure code.
  - Validated findings are persisted in `scan_findings` using safe normalized fields and a unique fingerprint per scan. Secret values, matches, raw scanner messages/JSON, and source snippets are not persisted, logged, or returned. Gitleaks findings use application severity `HIGH`; Trivy `UNKNOWN` and `NEGLIGIBLE` severities map to `INFO`.
  - Authenticated owner-scoped APIs provide scan-level finding lists with severity, scanner, and file-path filters, bounded pagination, and finding detail retrieval.
  - Automated tests cover parser validation, duplicate handling, ownership, authorization, worker persistence, and scanner failure cases. The Semgrep and Gitleaks Docker behavior remains covered by existing regression tests and smoke tests.
- **Upcoming milestone — Reports and Scoring**:
  - Add scan report generation and calculate a security score from persisted findings.
  - Security score calculation algorithm (0–100 scale based on severity weights: Critical -25, High -15, Medium -5, Low -1).
- **Verification Milestone**:
  - Verify the owner-scoped scan request lifecycle and persisted scanner outcomes with backend tests.
  - Scan a test project containing harmless secret-like data and an analyzable code pattern using the real Docker worker; confirm scanner results without retaining the dummy secret or raw matches.

---

### Phase 4: AI Microservice & Local Ollama Integration
- **Objective**: Provide a separate Python 3.12 FastAPI service for safe, schema-validated static finding explanations using local Ollama, integrated with owner-scoped persisted findings in Spring Boot.
- **Milestone 2.1 — AI Microservice Foundation (implemented)**:
  - Separate `ai-service/` application using FastAPI, Pydantic Settings, Uvicorn, HTTPX, and Pytest.
  - `GET /health` reports the service name and health status.
  - `GET /health/ollama` queries the configured Ollama `/api/tags` endpoint with bounded connection and request timeouts and checks for `qwen2.5-coder:3b` by default.
  - Ollama connectivity failures and missing models return controlled `503` responses. Model downloads are never initiated automatically.
  - Tests use mocked HTTPX transports and do not require Ollama or internet access.
  - Windows PowerShell setup and start instructions are in `ai-service/README.md`.
- **Milestone 2.2 — AI Analysis API (implemented)**:
  - `POST /api/v1/analyze` accepts the Java backend's normalized finding fields with bounded input and optional snippet limits.
  - Uses only the configured Ollama URL/model and its structured JSON generation API; response fields are validated before returning.
  - Gitleaks message/metadata/snippet values are never sent to Ollama; common credential patterns are redacted from other inputs and generated fields.
  - Ollama calls have configured generation timeout/token limits, bounded response size, and controlled error responses. Submitted and generated code is never executed.
- **Milestone 2.3 — Java–Python AI Integration (implemented)**:
  - Authenticated `POST /api/v1/findings/{findingId}/analyze` and `GET /api/v1/findings/{findingId}/analysis` routes enforce finding ownership.
  - The backend sends only normalized, persisted finding fields to FastAPI; snippets, raw scanner output, Gitleaks matches, and caller JWTs are omitted.
  - Results and controlled lifecycle/failure states are persisted once per finding. Duplicate processing is rejected, completed results are reused, retries are bounded, and stale pending claims can be reclaimed.
  - Service URL and bounded timeouts are environment-configured; integration responses do not disclose upstream bodies or internal service URLs. Fixes are never automatically executed.
- **Future Phase 4 work**:
  - RAG, ChromaDB, LangChain, frontend, and applying generated fixes remain separate future work.
- **Verification Milestone**:
  - Run the service Pytest suite with Python 3.12.
  - Verify health and Ollama availability plus analysis success, validation, redaction, timeouts, unavailable Ollama, malformed output, and request/response limits using mocked HTTPX responses.

---

### Phase 5: RAG Pipeline & Context-Aware Code Fixes
- **Objective**: Implement project-level semantic search using ChromaDB and generate unified diff code patches.
- **Key Deliverables**:
  - Code chunker utility splitting files by function and class structures with line number metadata.
  - ChromaDB vector store collection management per project version.
  - Context retrieval service: given a vulnerability, fetches related project files (types, helpers).
  - AI fix generator producing replacement code and valid unified diff patches.
  - Fix decision service: Accept (creates `v2` with patch applied), Reject, or Edit patch.
  - Rollback service allowing instant revert to any prior version.
- **Verification Milestone**:
  - Trigger "Suggest Fix" on a sample vulnerability; verify ChromaDB retrieves imported types.
  - Accept the fix; verify that `v2` is created on disk and in the database with the patch cleanly applied, leaving `v1` intact.

---

### Phase 6: Frontend Single Page Application (React + Tailwind CSS)
- **Objective**: Build a clean, portfolio-quality React web application with responsive dark/light styling and interactive code tools.
- **Key Deliverables**:
  - Vite + React + Tailwind CSS project setup.
  - Authentication views: Login, Register, Protected Route wrapper.
  - Main Dashboard: Project grid, security score gauges, recent scan summaries.
  - Project Detail View: Interactive file explorer and code viewer.
  - Scan Results View: Severity filters (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`), secret cards with reveal toggle.
  - Side-by-Side Diff Viewer: Visual comparison with Accept, Reject, and Manual Edit controls.
  - Version History & Rollback UI.
- **Verification Milestone**:
  - End-to-end user navigation: login, project creation, ZIP upload, scan trigger, and fix application entirely through the browser.

---

### Phase 7: Docker Isolated Testing Sandbox
- **Objective**: Run untrusted project test suites in an isolated, resource-constrained, zero-network Docker container.
- **Key Deliverables**:
  - Dockerfiles for isolated test runners (`runner-java`, `runner-node`, `runner-python`).
  - Docker container execution manager in Spring Boot with `--network none`, `--memory 512m`, `--cpus 1.0`, `--read-only`, and 30s timeout watchdog.
  - Output log streamer capturing stdout/stderr into `test_runs` database records and frontend terminal view.
- **Verification Milestone**:
  - Execute a legitimate test suite; verify green pass status and captured logs.
  - Execute a test attempting an outbound network request or infinite loop; verify network is blocked and timeout safely kills the container.

---

### Phase 8: PDF Security Reports & Collaboration
- **Objective**: Generate downloadable PDF security reports and enable reviewer invitation with line-specific comments.
- **Key Deliverables**:
  - PDF generation engine using OpenPDF with custom branding, executive summary, charts, and remediation checklists.
  - Reviewer invitation system: invite registered users by email with `VIEW` or `REVIEW` permissions.
  - Version-specific inline commenting and formal approval workflows.
  - Optional GitHub repository export of remediated projects.
- **Verification Milestone**:
  - Generate and download a PDF report; verify all sections and scores match DB records.
  - Invite a second test user; log in as reviewer and submit an inline review comment.

---

### Phase 9: End-to-End Validation & Interview Polish
- **Objective**: Full end-to-end regression testing, security review, and documentation polish for technical interviews.
- **Key Deliverables**:
  - Comprehensive end-to-end testing with real-world vulnerable test repositories (Java, Node, Python).
  - Technical Interview Guide: Architecture decisions, trade-offs, security model explanations, and talking points.
  - Final project documentation and cleanup.
