# System Architecture — CodeSentinel AI

## 1. High-Level Architectural Diagram

```
                                  +----------------------------------------------------+
                                  |              React Frontend (SPA)                  |
                                  |  (React 18, Tailwind CSS, React Router, Diff View) |
                                  +-------------------------+--------------------------+
                                                            |
                                                            | HTTPS / REST (JWT)
                                                            v
+--------------------------------------------------------------------------------------------------------------------+
|                                      Java 21 Spring Boot Main Backend                                              |
|                                                                                                                    |
|  +------------------------+  +-------------------------+  +------------------------+  +-------------------------+  |
|  |   Security / Auth      |  |   Project & Version     |  |   Static Scan          |  |   Report Generation     |  |
|  |  (Spring Security+JWT) |  |   Service (ZIP/Storage) |  |   Orchestrator         |  |   (OpenPDF / JSON)      |  |
|  +------------------------+  +-------------------------+  +-----------+------------+  +-------------------------+  |
|                                                                       |                                            |
|                                                                       | Spawns Scanner Processes / Containers      |
|                                                                       v                                            |
|                                                     +-----------------+------------------+                         |
|                                                     | Static Scanners (Semgrep, Gitleaks, Trivy)|                                             |
+-------------------+---------------------------------+-----------------+------------------+-------------------------+
                    |                                                   |
                    | JDBC / JPA (Spring Data JPA)                                          | REST / JSON (backend integration planned)
                    v                                                   v
+-------------------+--------------+                  +-----------------+--------------------------------------------+
|        PostgreSQL Database       |                  | Python FastAPI AI Service (analysis API implemented)         |
|                                  |                  |                                                              |
| - Users & Roles                  |                  |  +------------------------+    +--------------------------+  |
| - Projects & Versions            |                  |  | Planned RAG / Embedding|    | Health + analysis API   |  |
| - Vulnerabilities & Secrets      |                  |  | (LangChain + ChromaDB) |    | (HTTPX + Ollama)        |  |
| - Code Fixes & Reviews           |                  |  +------------------------+    +--------------------------+  |
| - Test Logs & Audit Records      |                  +--------------------------------------------------------------+
+----------------------------------+                                    |
                                                                        | HTTP (localhost:11434)
                                                                        v
+----------------------------------+                  +-----------------+--------------------------------------------+
|    Isolated Docker Test Worker   |                  |               Local Ollama Engine                            |
| (--network none, --memory 512m)  |                  |         (Code Model: qwen2.5-coder:3b)                       |
+----------------------------------+                  +--------------------------------------------------------------+
```

---

## 2. Component Responsibilities & Communication

### 2.1 React Frontend (Single Page Application)
- **Role**: Presentation and interaction layer for developers and reviewers.
- **Key Capabilities**:
  - Stateless session management via JWT stored in secure storage / application state.
  - Project workspace: file explorer tree, multi-file code viewer, upload modals.
  - Scan results dashboard: severity badges, filterable issue tables, and secret cards.
  - Interactive Diff Viewer (Monaco Editor / Diff viewer) with Accept, Reject, and Manual Edit controls.
  - Real-time test output terminal display.
  - PDF report download trigger.
- **Communication**: Communicates exclusively with the Spring Boot Backend via REST APIs using JSON payloads.

### 2.2 Java 21 Spring Boot Main Backend
- **Role**: Core application orchestrator, business logic engine, and security guardian.
- **Key Modules**:
  - `Security & Auth`: User authentication, BCrypt password hashing, JWT filter chain.
  - `Project Service`: Manages project lifecycle, safe ZIP extraction (`Zip Slip` prevention), and directory version trees.
  - `Scan Orchestrator`: Executes Semgrep, Gitleaks, and Trivy sequentially in isolated containers, validates and normalizes results, and stores safe finding fields in PostgreSQL.
  - `AI Gateway Service` (planned): Will coordinate with the Python AI microservice using sanitized inputs and validated responses.
  - `Sandbox Orchestrator`: Interacts with Docker Engine via Docker CLI/API to spin up ephemeral test containers with strict resource caps.
  - `Report Service`: Assembles vulnerability and compliance data into formatted PDF documents using OpenPDF.
- **Communication**:
  - Talks to PostgreSQL via Spring Data JPA / Hibernate.
  - Talks to Python AI Service via internal REST calls (`RestClient` / `WebClient`).
  - Controls Docker CLI for scanning and test isolation.

### 2.3 PostgreSQL Database
- **Role**: Relational persistence layer storing users, projects, version records, scan reports, normalized findings, patch history, and review comments.
- **Integrity**: Enforces ACID compliance, foreign-key constraints, cascading deletes where appropriate, and unique indexing on emails and project paths.

### 2.4 Python FastAPI AI Microservice
- **Role**: Separate Python service for local LLM-powered finding explanations.
- **Implemented**:
  - **FastAPI + Pydantic Settings**: Provides typed health endpoints and reads configuration from environment variables or the service-local `.env`.
  - **Ollama availability check**: Uses HTTPX with connection/request timeouts to query `/api/tags` and check for the configured model. It does not download the model.
  - `GET /health` reports service health; `GET /health/ollama` reports model availability or returns a controlled `503 Service Unavailable` response without exposing internal errors or configuration.
  - `POST /api/v1/analyze` accepts bounded normalized findings and returns validated explanations, remediation advice, and an optional illustrative fix. Ollama configuration is server-controlled; submitted or generated code is never executed.
  - Gitleaks finding text and metadata are excluded from Ollama prompts; common credential patterns in other untrusted text and generated responses are redacted. Request and generated response sizes are bounded.
  - Tests mock HTTPX responses and require neither Ollama nor internet access.
- **Not implemented in this milestone**: Spring Boot integration, RAG, code chunking, ChromaDB, LangChain, persistence, or frontend. The analysis endpoint returns advisory fixes only and does not apply or execute them.

### 2.5 Security Scanners
- **Semgrep**: Runs first using the trusted ruleset and maps its severities to the application model.
- **Gitleaks**: Runs after Semgrep to detect exposed secrets. Its findings are normalized without retaining secret values or matches and use application severity `HIGH`.
- **Trivy**: Runs after Gitleaks to scan supported dependency manifests for known vulnerabilities. It uses the pinned official image `aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c`, an administrator-refreshed host vulnerability-database cache mounted read-only, and no scanner network access. `CRITICAL`, `HIGH`, `MEDIUM`, and `LOW` map directly; `UNKNOWN` and `NEGLIGIBLE` map to application severity `INFO`.
- All scanner containers use read-only source mounts, a read-only container filesystem, a non-root UID/GID, dropped capabilities, no-new-privileges, and configured CPU, memory, process, output, and execution-time limits. Uploaded source code is not executed.

### 2.6 Isolated Docker Test Worker
- **Role**: Safely executes untrusted test suites (`mvn test`, `pytest`, `npm test`).
- **Sandboxing Flags**:
  - `--network none`: Container has zero internet or LAN access.
  - `--memory 512m --cpus 1.0`: Strict memory and CPU resource caps.
  - `--read-only`: Read-only root filesystem with write access limited to ephemeral `/tmp`.
  - Execution timeout: 30-second kill signal to prevent infinite loops.

---

## 3. Database Entity Relationship (ER) Design

The ER diagram below is the intended broader product model. In the current backend, uploaded snapshots are represented by `project_uploads`; the implemented scan foundation adds `scan_runs` with a required `upload_id` foreign key. Findings and scanner output are not persisted yet.

```
+----------------------------------+
|              users               |
+----------------------------------+
| id: UUID [PK]                    |
| email: VARCHAR(255) [UNIQUE]     |
| password_hash: VARCHAR(255)      |
| full_name: VARCHAR(100)          |
| role: VARCHAR(20) [USER, ADMIN]  |
| created_at: TIMESTAMP            |
| updated_at: TIMESTAMP            |
+----------------------------------+
                |
                | 1:N
                v
+----------------------------------+
|             projects             |
+----------------------------------+
| id: UUID [PK]                    |
| owner_id: UUID [FK -> users.id]  |
| name: VARCHAR(150)               |
| description: TEXT                |
| repository_url: VARCHAR(500)     |
| created_at: TIMESTAMP            |
| updated_at: TIMESTAMP            |
+----------------------------------+
        |                  |
        | 1:N              | 1:N
        v                  v
+-----------------------+  +---------------------------------------+
|   project_versions    |  |          project_reviewers            |
+-----------------------+  +---------------------------------------+
| id: UUID [PK]         |  | id: UUID [PK]                         |
| project_id: UUID [FK] |  | project_id: UUID [FK -> projects.id]  |
| version_number: INT   |  | user_id: UUID [FK -> users.id]        |
| storage_path: VARCHAR |  | permission: VARCHAR(20) [VIEW, REVIEW]|
| commit_message: TEXT  |  | invited_at: TIMESTAMP                 |
| created_at: TIMESTAMP |  +---------------------------------------+
+-----------------------+
        |
        | 1:N
        +-----------------------------------+-----------------------------------+
        |                                   |                                   |
        v                                   v                                   v
+-----------------------+       +-----------------------+       +-----------------------+
|     scan_reports      |       |       test_runs       |       |    review_comments    |
+-----------------------+       +-----------------------+       +-----------------------+
| id: UUID [PK]         |       | id: UUID [PK]         |       | id: UUID [PK]         |
| version_id: UUID [FK] |       | version_id: UUID [FK] |       | version_id: UUID [FK] |
| scan_status: VARCHAR  |       | test_framework: STR   |       | author_id: UUID [FK]  |
| security_score: INT   |       | status: VARCHAR       |       | file_path: VARCHAR    |
| total_issues: INT     |       | exit_code: INT        |       | line_number: INT      |
| created_at: TIMESTAMP |       | logs_text: TEXT       |       | comment_text: TEXT    |
+-----------------------+       | duration_ms: BIGINT   |       | created_at: TIMESTAMP |
        |                       | created_at: TIMESTAMP |       +-----------------------+
        |                       +-----------------------+
        | 1:N
        +-----------------------------------+
        |                                   |
        v                                   v
+-----------------------+       +-----------------------+
|    vulnerabilities    |       |    secret_findings    |
+-----------------------+       +-----------------------+
| id: UUID [PK]         |       | id: UUID [PK]         |
| scan_report_id: [FK]  |       | scan_report_id: [FK]  |
| rule_id: VARCHAR(100) |       | rule_id: VARCHAR(100) |
| title: VARCHAR(255)   |       | secret_type: VARCHAR  |
| description: TEXT     |       | masked_value: VARCHAR |
| severity: VARCHAR(20) |       | file_path: VARCHAR    |
| file_path: VARCHAR    |       | line_number: INT      |
| line_number: INT      |       | start_column: INT     |
| code_snippet: TEXT    |       | end_column: INT       |
| status: VARCHAR(20)   |       | is_resolved: BOOLEAN  |
| created_at: TIMESTAMP |       | created_at: TIMESTAMP |
+-----------------------+       +-----------------------+
        |
        | 1:1
        v
+-----------------------+
|      code_fixes       |
+-----------------------+
| id: UUID [PK]         |
| vulnerability_id: [FK]|
| ai_explanation: TEXT  |
| root_cause: TEXT      |
| original_snippet: TEXT|
| suggested_snippet:TEXT|
| diff_patch: TEXT      |
| status: VARCHAR(20)   |
| created_at: TIMESTAMP |
+-----------------------+
```

---

## 4. REST API Endpoints Specification

### 4.1 Authentication (`/api/v1/auth`)
- `POST /register`: Request body: `{ email, password, fullName }`. Returns `201 Created` with user details.
- `POST /login`: Request body: `{ email, password }`. Returns `200 OK` with JWT token and user profile.
- `GET /me`: Returns the authenticated user's profile.

### 4.2 Projects & Versions (`/api/v1/projects`)
- `GET /`: List projects owned by or shared with current user.
- `POST /`: Create project metadata `{ name, description }`.
- `GET /{projectId}`: Get project details.
- `DELETE /{projectId}`: Delete project, its versions, and physical storage.
- `POST /{projectId}/uploads`: Authenticated multipart upload with a `file` ZIP part. The service validates archive entries, extracts regular source files into a generated directory outside the application source tree, and records upload metadata.
- `GET /{projectId}/uploads`: List upload history for the project owner.
- `GET /{projectId}/uploads/{uploadId}`: Get upload metadata for the project owner.
- ZIP uploads are limited to 50 MB compressed, 1,000 extracted files, 250 MB total extracted data, and 50 MB per file by default. The storage root and limits are configurable under `codesentinel.upload`; uploaded files are never executed or stored in PostgreSQL.
- `POST /{projectId}/import-github`: Import public repository via GitHub URL.
- `GET /{projectId}/versions`: List all versions of a project.
- `GET /{projectId}/versions/{versionId}/files`: Get tree structure of project version files.
- `GET /{projectId}/versions/{versionId}/file-content`: Read content of a specific file.
- `POST /{projectId}/versions/{versionId}/rollback`: Set active working tree to chosen version.

### 4.3 Scans & Security Reports (`/api/v1/scans`)
- **Implemented scan-management foundation** (all endpoints require authentication and enforce project ownership):
  - `POST /projects/{projectId}/uploads/{uploadId}/scans`: Create a `QUEUED` scan request and return `202 Accepted`.
  - `GET /projects/{projectId}/uploads/{uploadId}/scans`: List scan requests for that owned upload.
  - `GET /scans/{scanId}`: Retrieve an owned scan request.
  - `GET /scans/{scanId}/findings`: List findings for an owned scan, with optional exact `severity`, `scanner`, and relative `filePath` filters. Results are ordered consistently and paginated with a default size of 50 and maximum size of 100.
  - `GET /findings/{findingId}`: Retrieve one finding only when its scan belongs to the authenticated user.
  - Scan records refer to one specific completed `ProjectUpload` snapshot. Responses contain IDs, status, timestamps, summary counters, and safe per-scanner outcome metadata only.
- **Worker foundation**:
  - A separate host-side Java process can poll PostgreSQL when `codesentinel.scan-worker.enabled=true`; the API process leaves this disabled.
  - The worker atomically claims queued scans under a project row lock, validates the completed upload and resolves its UUID storage reference under the configured upload root.
  - The development worker runs Semgrep first using the pinned official non-root image `semgrep/semgrep:1.182.1-nonroot@sha256:0ed16bbbb23e5bf7c442df9144704d74b54ad2d003094e09905130d642f22473`, then Gitleaks using `ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f`, and finally Trivy using the pinned image above. Tests select a placeholder executor and never require Docker.
  - Each scanner stages the selected upload into bounded temporary storage, mounts the staged snapshot at `/src` read-only, and removes its temporary files and container after execution.
  - All scanners run with no network, a read-only container root, explicit non-root users, dropped capabilities, no-new-privileges, CPU/memory/process limits, and bounded output and execution time. Trivy also receives only the trusted vulnerability database cache mounted read-only. The scanners do not execute uploaded scripts or build steps.
  - A claim token prevents stale workers from updating a newer claim. A separate heartbeat renews active leases; expired leases are marked `FAILED`. The worker and backend must use the same PostgreSQL database and upload-storage directory.
  - Per-scanner outcomes are persisted in the `scan_outcomes` table. They contain scanner identity, execution status, deduplicated finding count, timestamps, and a controlled failure code; they do not contain raw scanner output or secrets.
  - Validated individual findings are persisted in `scan_findings`. Stored fields are limited to scanner, rule ID, application severity, relative path, optional source coordinates, a generic scanner-specific description, a deterministic fingerprint, and creation time. A unique scan/fingerprint constraint prevents duplicate rows. Raw scanner messages, secret values, matches, source snippets, and raw JSON are not stored, logged, or returned.
  - Findings APIs enforce scan ownership for both listing and detail retrieval. Filtering supports severity, scanner, and exact relative path. Result pages are bounded; returned descriptions are generic and do not contain scanner-provided messages.
  - Trivy database updates are a separate administrative operation. The configured host cache must contain a valid, unexpired database before a scan; otherwise Trivy returns a controlled `DATABASE_UNAVAILABLE` outcome. Set `TRIVY_DATABASE_DIRECTORY` to the trusted cache root when it is not the default `${user.home}/.cache/trivy`. For a manual refresh, use the pinned image with network access only in this maintenance command, never in the scanner container:
    ```powershell
    New-Item -ItemType Directory -Force $env:TRIVY_DATABASE_DIRECTORY
    docker run --rm --mount "type=bind,source=$env:TRIVY_DATABASE_DIRECTORY,target=/root/.cache/trivy" aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c --cache-dir /root/.cache/trivy image --download-db-only
    docker run --rm --mount "type=bind,source=$env:TRIVY_DATABASE_DIRECTORY,target=/root/.cache/trivy" aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c --cache-dir /root/.cache/trivy image --download-java-db-only
    ```
  - To run locally from `backend/`, package with `mvn package`, then start the API with `java -jar target/codesentinel-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev`. In another terminal, start the worker with `java -jar target/codesentinel-backend-0.0.1-SNAPSHOT.jar --codesentinel.worker-process=true --codesentinel.scan-worker.enabled=true --spring.profiles.active=dev`.
- **Planned, not yet implemented**:
  - `GET /projects/{projectId}/versions/{versionId}/report`: Retrieve scan report with calculated security score.
  - Calculate and expose a security score from persisted findings.

### 4.4 AI Explanations & Patch Management (`/api/v1/ai`)
- `POST /vulnerabilities/{vulnId}/explain`: Generate developer-friendly explanation and root cause via local LLM.
- `POST /vulnerabilities/{vulnId}/suggest-fix`: Generate code patch and unified diff using RAG context.
- `POST /fixes/{fixId}/apply`: Apply fix, creating a new project version (`v2`, `v3`, etc.).
- `POST /fixes/{fixId}/reject`: Mark fix as rejected.
- `PUT /fixes/{fixId}/edit`: Save user-customized patch before applying.

### 4.5 Sandboxed Test Execution (`/api/v1/tests`)
- `POST /versions/{versionId}/run`: Execute test suite in Docker container (`--network none`).
- `GET /runs/{runId}/logs`: Retrieve stdout/stderr logs and pass/fail summary.

### 4.6 Reports & Collaboration (`/api/v1/reports` & `/api/v1/reviews`)
- `GET /reports/{reportId}/pdf`: Download formatted, branded PDF report.
- `POST /projects/{projectId}/invite`: Invite reviewer by email (`VIEW` or `REVIEW` permissions).
- `POST /versions/{versionId}/comments`: Submit inline code review comment.

---

## 5. AI and RAG Pipeline Specification

### Step 1: Ingestion & Pre-RAG Secret Scrubbing
1. When a project version is uploaded, files are parsed.
2. Gitleaks scan results identify secret positions (`file_path`, `line`, `col`).
3. Discovered secrets are sanitized to `[REDACTED_SECRET_<HASH>]` before text chunking.

### Step 2: Code Chunking & Embeddings
1. Source files are segmented using AST-aware splitters (Python, Java, JS/TS).
2. Chunks retain metadata: `project_id`, `version_id`, `file_path`, `start_line`, `end_line`, `symbol_name`.
3. Chunks are embedded and stored in ChromaDB in a collection named `proj_<projectId>_v<versionNumber>`.

### Step 3: Semantic Context Retrieval
1. When a user requests an explanation or fix for a vulnerability at `file_path:line_number`, the query combines the vulnerability rule, affected snippet, and imported symbols.
2. ChromaDB returns top $k=3$ nearest semantic chunks (e.g., utility functions, type definitions, validation helpers).

### Step 4: Prompt Construction & LLM Inference
1. System prompt strictly constrains Ollama model (`qwen2.5-coder:3b`) to output a valid JSON structure:
   ```json
   {
     "explanation": "Clear explanation of the flaw and exploit mechanism...",
     "rootCause": "Direct reason why the vulnerability occurs...",
     "suggestedSnippet": "Refactored, secure code block...",
     "unifiedDiff": "--- original\n+++ patched\n@@ -10,3 +10,3 @@\n..."
   }
   ```
2. Low temperature ($0.15$) ensures reproducible, syntactically valid code patches.
3. Response is validated by Pydantic before transmission back to the Spring Boot backend.

---

## 6. Docker Sandboxing & Isolated Testing Model

To guarantee host safety:
- **Image Types**: Pre-built base test images (`codesentinel-runner-java`, `codesentinel-runner-node`, `codesentinel-runner-python`).
- **Isolation Directives**:
  ```bash
  docker run --rm \
    --network none \
    --memory 512m \
    --cpus 1.0 \
    --pids-limit 64 \
    --user 1000:1000 \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=64m \
    -v "storage/projects/<projId>/v<verNum>:/workspace:ro" \
    codesentinel-runner-<env>
  ```
- **Process Supervision**: Backend executes test containers with a strict 30-second watchdog timer. If execution exceeds 30 seconds, the container is forcibly terminated (`docker kill`) and logged as a timeout failure.
