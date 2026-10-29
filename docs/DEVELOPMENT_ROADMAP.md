# Development Roadmap — CodeSentinel AI

This document establishes the step-by-step implementation plan for CodeSentinel AI. Each phase is self-contained, tested, and verified before proceeding to the next.

---

## Phase Summary Matrix

| Phase | Description | Key Deliverables | Verification Milestone |
|:---:|---|---|---|
| **Phase 0** | **Architecture & Blueprint** | System architecture, ER diagrams, API specs, environment setup. | Workspace initialized, docs approved. |
| **Phase 1** | **Backend Foundation & Auth** | Spring Boot 3, PostgreSQL, Spring Security, JWT, User entity. | User registration/login tests passing, JWT validated. |
| **Phase 2** | **Project & Secure Ingestion** | Projects, Versions, ZIP file upload, `Zip Slip` prevention. | Upload sample ZIP, verify safe extraction and DB records. |
| **Phase 3** | **Static Security Scanners** | Scan-management foundation implemented; Gitleaks, Semgrep, secret masking, and finding normalization remain. | Scan requests are owner-scoped and linked to uploads; later verify masked findings from real scans. |
| **Phase 4** | **AI Microservice & Ollama** | Python 3.12 FastAPI, Ollama `qwen2.5-coder:3b`, JSON schema. | Explanations and root cause generated via local LLM. |
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
- **Objective**: Manage scan requests against specific uploaded snapshots, then integrate isolated Gitleaks and Semgrep analysis, redact discovered secrets, and persist structured findings.
- **Implemented foundation**:
  - `ScanRun` records reference a completed `ProjectUpload` and begin in `QUEUED` status.
  - Authenticated, owner-scoped endpoints create scan requests, list an upload's scan history, and retrieve scan details.
  - Safe response metadata includes lifecycle timestamps and zero-initialized summary counters; raw scanner output and secrets are not stored.
  - Project deletion removes scan rows before upload metadata while retaining the existing post-commit uploaded-file cleanup.
- **Remaining scanner work**:
  - Database entities: `ScanReport`, `Vulnerability`, and `SecretFinding`.
  - Scanner service executing Gitleaks CLI with JSON output formatting.
  - Secret sanitizer utility: masks detected secrets (`[REDACTED_SECRET_<HASH>]`) before persistence and indexing.
  - Semgrep execution engine running OWASP Top 10 rulesets, parsing JSON findings.
  - Security score calculation algorithm (0–100 scale based on severity weights: Critical -25, High -15, Medium -5, Low -1).
  - Scan endpoints: trigger scan and retrieve findings.
- **Verification Milestone**:
  - Verify owner-scoped scan request lifecycle and upload linkage (implemented and covered by backend tests).
  - Scan a mock project containing intentional hardcoded API keys and an SQL injection flaw.
  - Confirm Gitleaks detects the secret, the secret is masked in the database, and Semgrep flags the SQL injection.

---

### Phase 4: AI Microservice & Local Ollama Integration
- **Objective**: Build the Python 3.12 FastAPI microservice and connect it to local Ollama (`qwen2.5-coder:3b`) for structured vulnerability explanations.
- **Key Deliverables**:
  - FastAPI application structure (`main.py`, `app/api/`, `app/services/`, `app/models/`).
  - Ollama client communication service via HTTP (`localhost:11434/api/generate` or official ollama library).
  - Pydantic models enforcing strict JSON outputs (`explanation`, `rootCause`, `suggestedSnippet`).
  - Prompt engineering templates tailored for security analysis and beginner-friendly mentorship.
  - Integration between Spring Boot backend and FastAPI service (`POST /vulnerabilities/{id}/explain`).
- **Verification Milestone**:
  - Run Pytest suite for FastAPI endpoints.
  - Verify an explanation is generated for a flagged vulnerability without any hallucinated formatting.

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
