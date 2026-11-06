# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

ask-my-docs is a RAG ("ask questions about your documents") app, currently at the skeleton stage: no RAG, ingestion or chat code exists yet. It is a monorepo with two independent projects and no root-level build:

- `backend/` — Spring Boot 4.1 + Spring AI 2.0 (`spring-ai-starter-model-ollama`, Spring MVC), Java 21, Maven 3.10 via wrapper. Package root `com.alber.backend`.
- `frontend/` — Angular 22 standalone app (no NgModules, signals, `@angular/build` application builder), SCSS, Vitest for unit tests. Node `^22.22.3 || ^24.15.0 || >=26`.

The backend talks to a **local Ollama** instance (`spring.ai.ollama.base-url=http://localhost:11434`, chat model `qwen3:8b`, in `backend/src/main/resources/application.properties`). Ollama must be running with the model pulled for the backend to answer chat requests. The backend starts and `./mvnw test` passes without it. Pull the model once:

```bash
ollama pull qwen3:8b
```

## Commands

Backend (run from `backend/`):

```bash
./mvnw spring-boot:run                                   # run the API
./mvnw test                                              # all tests
./mvnw test -Dtest=BackendApplicationTests               # one test class
./mvnw test -Dtest=BackendApplicationTests#contextLoads  # one test method
./mvnw package                                           # build jar
```

Frontend (run from `frontend/`):

```bash
npm start                                         # ng serve on http://localhost:4200
npm run build                                     # production build (budgets: 500kB warn / 1MB error initial)
npm test                                          # Vitest via ng test (watch mode)
npx ng test --watch=false --include app/app.spec.ts   # single spec; path is relative to src/
npx prettier --write .                            # format
```

Repo root:

```bash
git config core.hooksPath .githooks   # once per clone: enables the gitleaks pre-commit hook
sh scripts/test-gitleaks-hook.sh      # tests for the hook, the CI scan script and .env setup (CI runs them too)
sh scripts/gitleaks-scan-history.sh   # scan the full history for secrets (what CI runs)
```

## Conventions

- Lombok is on the annotation-processor path for both main and test compilation.
- Frontend: Prettier (`printWidth` 100, single quotes, Angular parser for HTML) and `.editorconfig` (2-space indent). Component selector prefix `app`, component styles SCSS.
- Secrets: real values go in the gitignored root `.env`; `.env.example` holds placeholders only. The gitleaks pre-commit hook (`.githooks/pre-commit`, needs `brew install gitleaks`) blocks staged secrets, and `.github/workflows/gitleaks.yml` runs `scripts/gitleaks-scan-history.sh` (full history, all refs, merge commits included) with a checksum-pinned gitleaks on every push and PR; change the scan there, not in the workflow, so the tests cover it. When bumping gitleaks, update `GITLEAKS_VERSION` and `GITLEAKS_SHA256` there together. False positives go in a `gitleaks:allow` comment or a value-specific `.gitleaks.toml` allowlist, not `.gitleaksignore` fingerprints (too broad) or `--no-verify` (CI would still fail). Never add real-looking tokens to tests or fixtures; build fake ones at runtime as `scripts/test-gitleaks-hook.sh` does.
- Ignore rules: repo-wide noise (OS, JetBrains, `.env`, `.claude/settings.local.json`) in the root `.gitignore`; build output in `backend/.gitignore` and `frontend/.gitignore`.
- Remote: `github.com/albercode10/ask-my-docs`, default branch `main`.

## Workflow

Work is tracked in Linear: team `ALB`, project `ask-my-docs` (`P-ALB-1`), 2-week cycles. Milestones, in order: **Foundations** (repo hygiene, local dev setup, backend–frontend wiring) → **RAG MVP** (ingestion, embeddings, vector store, ask endpoint) → **Chat UI** (upload and chat pages, streamed answers with sources) → **Polish** (tests, RAG evaluation, observability, demo).

- Each issue gets its own branch from an up-to-date `main`, named with the issue's Linear branch name (`albercode10/alb-<N>-<slug>`). `/feature` creates it right after the plan is approved; don't create it by hand beforehand.
- Reference the issue in the PR description with `Fixes ALB-<N>` so the Linear–GitHub integration links and closes it.
- Design and tooling decisions live in the Linear issues; read the issue before starting work on it.
- Deliver tickets with `/feature ALB-<N>` (the developer's user-level skill, not in this repo): approved plan → failing tests → implementation → `.http` requests → fresh-eyes review. `ALB-<N>` keys are Linear issues. The approved plan lives in `docs/plans/ALB-<N>.md`; when it changes, update it and the tests before the code.
- Every new or changed endpoint comes with tests and a request file in `backend/http/` (IntelliJ HTTP Client, `{{baseUrl}}` from `http-client.env.json`).
- `./mvnw test` never calls real Ollama: stub `ChatModel` / `EmbeddingModel` with `@MockitoBean`. Real model behavior is checked through the `.http` files.
- Don't commit, push or open a PR until the developer asks.

## Planned

Decided in Linear but not in the repo yet — do not assume these exist:

- Postgres + pgvector via Docker Compose for local dev; Ollama stays native on the host (ALB-6, ALB-10).
- Pinned tool versions: SDKMAN for Java 21, a Node version file (ALB-25).
- Spotless for Java, ESLint + Prettier for Angular, enforced in CI (ALB-26); GitHub Actions build/test CI (ALB-8). The only workflow so far is the gitleaks scan.
- Dependabot (ALB-27).
- Backend `/health` endpoint, CORS and Angular dev proxy (ALB-7).
- Testcontainers integration tests (ALB-16), Playwright E2E (ALB-29), RAG evaluation set (ALB-17).
- Observability: Grafana `otel-lgtm` in Docker Compose, OTLP export, Spring AI observations, Grafana MCP (ALB-18–22); optional Langfuse (ALB-23).
- Docs-as-code in the repo plus Linear Docs for planning (ALB-24).
