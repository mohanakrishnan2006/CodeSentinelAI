# CodeSentinel AI 🛡️

> **An AI-powered source-code quality and security analysis platform featuring local LLMs, private RAG context retrieval, and zero-network Docker sandboxing.**

---

## 📖 Overview

**CodeSentinel AI** is an intelligent, developer-centric security auditing and code quality platform. It enables software developers to upload source code, identify vulnerabilities (OWASP Top 10) and exposed secrets (API keys, credentials), receive clear explanations powered by local AI, safely preview side-by-side patch diffs, and run test suites inside an isolated, resource-capped Docker sandbox.

### Key Capabilities
- 🔒 **Private & Local AI**: Powered by local LLMs (via Ollama) and an isolated ChromaDB RAG pipeline—your source code is never transmitted to external AI APIs.
- 🔍 **Multi-Engine Static Scanning**: Automated vulnerability scanning with Semgrep and secret detection with Gitleaks.
- 🛡️ **Zero-Network Docker Sandbox**: Untrusted code and tests execute in isolated, resource-limited Docker containers (`--network none`, `--memory 512m`).
- 🔄 **Code Versioning & Rollback**: Automatic, immutable version snapshots (`v1`, `v2`, ...) created upon applying suggested fixes with one-click rollback.
- 📊 **Security Reports**: Branded, downloadable executive and technical PDF reports with vulnerability breakdowns and remediation advice.
- 👥 **Optional Peer Review**: Invite teammates to review, comment on specific lines, and approve version snapshots.

---

## 🏗️ Architecture

```
React 18 Frontend (Tailwind CSS, Diff Viewer)
      │
      ▼ (HTTPS / REST + JWT)
Java 21 Spring Boot Main Backend
      ├── PostgreSQL Database (Relational storage, users, scan findings)
      ├── Static Scanners (Gitleaks, Semgrep CLI)
      ├── Isolated Docker Test Workers (--network none, 512MB RAM)
      └── Python 3.12 FastAPI AI Service
            ├── Local Ollama Engine (qwen2.5-coder:3b)
            └── Local ChromaDB Vector Store (RAG code chunking & semantic search)
```

---

## 📚 Project Documentation

Detailed architecture blueprints and guides are available in the [`docs/`](docs/) directory:
- [PROJECT_OVERVIEW.md](docs/PROJECT_OVERVIEW.md) — Product mission, target personas, 22 application modules, and security principles.
- [ARCHITECTURE.md](docs/ARCHITECTURE.md) — Technical architecture, component boundaries, database ER schema, REST API design, and sandboxing model.
- [DEVELOPMENT_ROADMAP.md](docs/DEVELOPMENT_ROADMAP.md) — Phased implementation plan, milestones, and verification checklists.

---

## 🛠️ Technology Stack

| Layer | Technologies |
|---|---|
| **Frontend** | React 18, JavaScript, Tailwind CSS, React Router, Axios, Monaco / Diff Viewer |
| **Main Backend** | Java 21 LTS, Spring Boot 3, Maven, Spring Security (JWT), Spring Data JPA, Hibernate |
| **Database** | PostgreSQL 16 |
| **AI Microservice** | Python 3.12, FastAPI, Pydantic, Uvicorn |
| **Local AI & RAG** | Ollama (`qwen2.5-coder:3b`), ChromaDB, LangChain |
| **Security Scanners** | Gitleaks, Semgrep, Trivy |
| **Sandboxing & Testing** | Docker (`--network none`), JUnit 5, Pytest |
| **Reports** | OpenPDF / iText |

---

## 🚀 Development Status

We are developing CodeSentinel AI incrementally following our review-driven workflow:

- [x] **Phase 0**: Architecture, Blueprint & Documentation Setup
- [ ] **Phase 1**: Backend Foundation, Authentication & Database Design *(Next)*
- [ ] **Phase 2**: Project Management & Secure File Ingestion
- [ ] **Phase 3**: Static Security Scanners Pipeline
- [ ] **Phase 4**: AI Microservice & Local Ollama Integration
- [ ] **Phase 5**: RAG Pipeline & Context-Aware Code Fixes
- [ ] **Phase 6**: Frontend Single Page Application
- [ ] **Phase 7**: Docker Isolated Testing Sandbox
- [ ] **Phase 8**: PDF Security Reports & Collaboration
- [ ] **Phase 9**: End-to-End Validation & Technical Interview Polish
