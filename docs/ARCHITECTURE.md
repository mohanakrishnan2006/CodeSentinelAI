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
|                                                     | Static Scanners (Gitleaks, Semgrep)|                         |
+-------------------+---------------------------------+-----------------+------------------+-------------------------+
                    |                                                   |
                    | JDBC / JPA (Spring Data JPA)                      | REST / JSON (Internal HTTP Client)
                    v                                                   v
+-------------------+--------------+                  +-----------------+--------------------------------------------+
|        PostgreSQL Database       |                  |            Python FastAPI AI Microservice                    |
|                                  |                  |                                                              |
| - Users & Roles                  |                  |  +------------------------+    +--------------------------+  |
| - Projects & Versions            |                  |  |  RAG / Embeddings      |    |  Ollama Client           |  |
| - Vulnerabilities & Secrets      |                  |  |  (LangChain + ChromaDB)|    |  (qwen2.5-coder:3b)      |  |
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
  - `Scan Orchestrator`: Executes Gitleaks and Semgrep CLI/containers on isolated project directories, parses JSON results, sanitizes detected secrets, and writes findings to PostgreSQL.
  - `AI Gateway Service`: Coordinates with the Python AI microservice, passing masked snippets and retrieving structured explanations and diffs.
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
- **Role**: Specialized service for code semantics, vector embeddings, RAG queries, and LLM inference.
- **Key Capabilities**:
  - **FastAPI + Pydantic**: Exposes typed, high-performance REST endpoints with strict request/response validation.
  - **Code Chunking**: Uses LangChain language-aware text splitters to partition source code along class, function, or logical block boundaries.
  - **Vector Storage (ChromaDB)**: Embeds and stores code representations, isolated per project version collection.
  - **Semantic Context Retrieval**: Given a vulnerability location, retrieves related functions, imported symbols, and type definitions.
  - **Local Ollama Integration**: Prompts `qwen2.5-coder:3b` using strict system instructions to produce beginner-friendly explanations and unified diff patches.
- **Security Boundary**: Accepts **only pre-sanitized** code snippets with secrets already masked.

### 2.5 Security Scanners
- **Gitleaks**: Scans source trees for exposed secrets (tokens, AWS keys, database URIs, passwords). Executes first in the scan pipeline so discovered credentials can be redacted immediately.
- **Semgrep**: Lightweight, AST-based static analysis engine running OWASP Top 10, CWE Top 25, and secure coding rulesets.
- **Trivy**: Scans project dependency manifests (`pom.xml`, `package.json`, `requirements.txt`) for known CVEs.

### 2.6 Isolated Docker Test Worker
- **Role**: Safely executes untrusted test suites (`mvn test`, `pytest`, `npm test`).
- **Sandboxing Flags**:
  - `--network none`: Container has zero internet or LAN access.
  - `--memory 512m --cpus 1.0`: Strict memory and CPU resource caps.
  - `--read-only`: Read-only root filesystem with write access limited to ephemeral `/tmp`.
  - Execution timeout: 30-second kill signal to prevent infinite loops.

---

## 3. Database Entity Relationship (ER) Design

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
- `POST /{projectId}/upload`: Multipart upload of project ZIP file. Safe extraction creates `v1`.
- `POST /{projectId}/import-github`: Import public repository via GitHub URL.
- `GET /{projectId}/versions`: List all versions of a project.
- `GET /{projectId}/versions/{versionId}/files`: Get tree structure of project version files.
- `GET /{projectId}/versions/{versionId}/file-content`: Read content of a specific file.
- `POST /{projectId}/versions/{versionId}/rollback`: Set active working tree to chosen version.

### 4.3 Scans & Security Reports (`/api/v1/scans`)
- `POST /projects/{projectId}/versions/{versionId}/trigger`: Run Gitleaks + Semgrep scans asynchronously.
- `GET /projects/{projectId}/versions/{versionId}/report`: Retrieve scan report with calculated security score.
- `GET /reports/{reportId}/vulnerabilities`: List detected vulnerabilities (with severity filter).
- `GET /reports/{reportId}/secrets`: List detected secrets (masked values).

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
