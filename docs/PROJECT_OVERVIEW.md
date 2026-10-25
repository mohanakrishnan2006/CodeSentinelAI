# Project Overview — CodeSentinel AI

## 1. Executive Summary

**CodeSentinel AI** is an AI-powered source-code quality and security analysis platform designed for individual software developers, engineering teams, and code reviewers. It acts as an automated, intelligent security auditor and code mentor, empowering developers to upload their code, uncover vulnerabilities and exposed secrets, understand the security implications through local AI explanations, and safely review, edit, and apply proposed fixes.

Unlike generic linter integrations or cloud-dependent AI tools, CodeSentinel AI:
1. **Keeps Code Private**: Utilizes local AI models (via Ollama) and an isolated RAG (Retrieval-Augmented Generation) pipeline so proprietary source code is never sent to third-party public AI APIs.
2. **Prioritizes Defense-in-Depth**: Combines deterministic static analysis (Semgrep, Gitleaks, Trivy) with generative AI explanations and verified code diffs.
3. **Ensures Safe Execution**: Tests untrusted code exclusively in isolated, resource-constrained, zero-network Docker sandboxes.
4. **Is Usable Individually**: Functions completely as a standalone tool for a solo developer while offering optional collaboration through peer review invitations.

---

## 2. Target Personas

### Persona A: Solo Software Developer (Primary)
- **Goal**: Quickly scan a project repository or uploaded ZIP file for security vulnerabilities, API key leaks, and bad coding patterns before pushing to production or submitting for review.
- **Needs**: Clear explanations of *why* a pattern is dangerous, accurate patch suggestions, an easy side-by-side diff viewer, and the ability to test changes in an isolated sandbox with zero risk to their host machine.

### Persona B: Peer Reviewer / Technical Lead (Optional)
- **Goal**: Review specific versions of a shared project, inspect security scan results, audit proposed AI fixes, leave line-by-line comments, and approve or request revisions.
- **Needs**: Granular access control (restricted to explicitly invited projects and versions) and an audit trail of changes.

---

## 3. Core Requirements & Application Modules

CodeSentinel AI is structured into 22 cohesive modules:

| # | Module | Description |
|---|---|---|
| 1 | **Authentication & Authorization** | Stateless JWT authentication, secure password hashing (BCrypt), role-based permissions (User, Reviewer, Admin). |
| 2 | **User Dashboard** | High-level metrics: total projects, security scores, recent scans, critical vulnerability counts, and activity feeds. |
| 3 | **Project Management** | Create, view, update, and delete private software projects. |
| 4 | **Ingestion: ZIP & GitHub** | Upload zipped code packages or clone public repositories from GitHub with path validation and size guards. |
| 5 | **Source-Code Storage** | Isolated, organized filesystem storage with path-traversal protection (`Zip Slip` prevention). |
| 6 | **Code Quality Scanning** | Static analysis identifying code smells, anti-patterns, and language-specific standard violations. |
| 7 | **Security Vulnerability Scanning** | AST-based vulnerability analysis with Semgrep covering OWASP Top 10 and CWE Top 25. |
| 8 | **Secret & Credential Detection** | Gitleaks integration detecting 160+ token formats (API keys, private keys, database connection strings). |
| 9 | **AI-Powered Scan Explanations** | Natural language, beginner-friendly explanations of vulnerability mechanics and root causes via local LLM. |
| 10 | **RAG Context Retrieval** | Project-wide vector search (ChromaDB) providing relevant helper functions, types, and configurations to the LLM. |
| 11 | **Suggested Code Fixes** | LLM-generated patch suggestions formatted as unified diffs and replacement blocks. |
| 12 | **Diff Comparison View** | Side-by-side visual diffing comparing original code with proposed patches. |
| 13 | **Fix Decision Workflow** | Ability to Accept, Reject, or Manually Edit suggested patches. |
| 14 | **Version History & Rollback** | Immutable version snapshots (`v1`, `v2`, ...) created on fix application, with one-click rollback. |
| 15 | **Docker Isolated Testing** | Sandbox execution of test suites (`mvn test`, `pytest`, `npm test`) with zero network access and strict resource caps. |
| 16 | **API Testing** | Built-in endpoint verification and health probing for all microservices. |
| 17 | **Execution Logs Viewer** | Real-time and persisted stdout/stderr log streaming from test runner containers. |
| 18 | **Scan History & Trends** | Historical tracking of security scores and vulnerability counts across project versions. |
| 19 | **PDF Security Reports** | Branded, downloadable executive and technical PDF reports with vulnerability breakdowns and remediation advice. |
| 20 | **Reviewer Invitations** | Secure project sharing granting specific users view-only or reviewer privileges. |
| 21 | **Reviewer Comments & Approvals** | Line-specific review comments, feedback threads, and formal approval sign-offs on specific versions. |
| 22 | **GitHub Publishing (Optional)** | Scan-verified export of remediated repositories to GitHub. |

---

## 4. Security Principles & Non-Negotiable Guardrails

Security is paramount in CodeSentinel AI:

1. **Zero Host Execution of Untrusted Code**: Uploaded source code is **never** compiled, executed, or evaluated directly on the host machine or main backend JVM. Untrusted code runs only in an isolated Docker sandbox.
2. **Docker Sandbox Hardening**:
   - Zero network egress (`--network none`) prevents data exfiltration or malware downloads.
   - Resource limits (`--memory="512m"`, `--cpus="1.0"`) eliminate CPU starvation and fork bombs.
   - Ephemeral containers with automatic timeouts (30s) prevent lingering processes.
3. **Secret Masking & Pre-RAG Scrubbing**:
   - Secrets discovered by Gitleaks are instantly scrubbed before entering logs, reports, ChromaDB vector stores, or LLM prompts.
   - UI displays masked values (e.g., `ghp_****************************3a9F`) with explicit reveal permissions.
4. **Honest Security Posture**:
   - The platform **never** claims that an application is "100% secure" or "guaranteed bug-free".
   - Findings are explicitly classified as `PASSED`, `FAILED`, `NEEDS_MANUAL_REVIEW`, or `NOT_TESTED`.
5. **Data & Version Immutability**:
   - Original uploaded source files are preserved unmodified in Version 1.
   - Any accepted fix creates a new, distinct version snapshot (`v2`, `v3`), ensuring complete auditability and rollback capability.
6. **Path Traversal Protection**:
   - All ZIP file extraction routines validate canonical paths to strictly prevent `Zip Slip` vulnerabilities.

---

## 5. Hardware Profile & Operational Boundaries

Development environment target:
- **Operating System**: Windows 11 (64-bit)
- **CPU**: AMD Ryzen 5 7520U (4 Cores / 8 Threads)
- **Memory**: 16 GB LPDDR5 RAM
- **Graphics**: Integrated AMD Radeon 610M (shared memory)
- **Storage**: 512 GB SSD
- **AI Model Choice**: `qwen2.5-coder:3b` (via local Ollama), balancing low RAM consumption (~2.0 GB) with state-of-the-art coding and security analysis capabilities.
