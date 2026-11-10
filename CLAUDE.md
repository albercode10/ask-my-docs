# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

ask-my-docs is a RAG ("ask questions about your documents") app, currently at the skeleton stage: no RAG, ingestion or chat code exists yet. How the parts fit and the planned RAG flow: `docs/architecture.md`; decisions behind the stack: `docs/adr/`. It is a monorepo with two independent projects and no root-level build:

- `backend/` — Spring Boot 4.1 + Spring AI 2.0 (`spring-ai-starter-model-ollama`, Spring MVC), Java 21 (Temurin, pinned in root `.sdkmanrc`; `maven-enforcer-plugin` fails the build on any other major), Maven 3.10 via wrapper. Package root `com.alber.backend`.
- `frontend/` — Angular 22 standalone app (no NgModules, signals, `@angular/build` application builder), SCSS, Vitest for unit tests. Node 26, pinned in `frontend/.nvmrc` (Angular 22 accepts `^22.22.3 || ^24.15.0 || >=26`).

The backend talks to a **local Ollama** instance (`spring.ai.ollama.base-url=http://localhost:11434`, chat model `qwen3:8b`, in `backend/src/main/resources/application.properties`). Ollama must be running with the model pulled for the backend to answer chat requests. The backend starts and `./mvnw test` passes without it. Pull the model once:

```bash
ollama pull qwen3:8b
```

Local Postgres 18 + pgvector runs in Docker Compose (`compose.yaml` at the root, image pinned to an exact `pgvector/pgvector` tag plus its multi-arch index digest (bump both together), credentials from the root `.env` (`POSTGRES_PASSWORD` is empty in `.env.example` on purpose, so Compose refuses to start until one is set), healthcheck over TCP (the socket also answers for the image's temporary init server), port bound to `127.0.0.1`, named volume at `/var/lib/postgresql`, the PG 18 data root). `docker/postgres/init/01-create-vector-extension.sql` installs `vector` in the app database, only when the volume is empty. The backend doesn't connect to it yet: ALB-10 adds the datasource, the pgvector store and `spring-boot-docker-compose`.

## Commands

Backend (run from `backend/`):

```bash
./mvnw spring-boot:run                                   # run the API (dev profile: Swagger UI on)
./mvnw test                                              # all tests
./mvnw test -Dtest=BackendApplicationTests               # one test class
./mvnw test -Dtest=BackendApplicationTests#contextLoads  # one test method
./mvnw package                                           # build jar
./mvnw spotless:apply                                    # format Java (spotless:check only checks; verify runs it)
```

Frontend (run from `frontend/`):

```bash
npm start                                         # ng serve on http://localhost:4200
npm run build                                     # production build (budgets: 500kB warn / 1MB error initial)
npm test                                          # Vitest via ng test (watch mode)
npx ng test --watch=false --include app/app.spec.ts   # single spec; path is relative to src/
npm run format                                    # Prettier, write (format:check only checks)
npm run lint                                      # ng lint (ESLint + angular-eslint)
```

Repo root:

```bash
git config core.hooksPath .githooks   # once per clone: enables the gitleaks pre-commit hook
sh scripts/test-gitleaks-hook.sh      # tests for the hook, the CI scan script and .env setup (CI runs them too)
sh scripts/gitleaks-scan-history.sh   # scan the full history for secrets (what CI runs)
docker compose up -d --wait           # local Postgres + pgvector (needs .env); down keeps data, down -v deletes it
sh scripts/test-compose.sh            # smoke tests for compose.yaml (throwaway project and port; needs Docker running)
sh scripts/test-code-style.sh         # tests for Spotless, ESLint and Prettier on throwaway copies (needs Java 21 and frontend node_modules)
sh scripts/test-docs.sh               # checks ADRs, the ADR index, architecture diagrams and relative Markdown links (CI runs it)
```

## Conventions

- Lombok is on the annotation-processor path for both main and test compilation.
- Java style: Spotless with Palantir Java Format (versions in `backend/pom.xml` properties), plus unused-import removal, trailing whitespace and final newline. Run `./mvnw spotless:apply` after editing Java. IntelliJ formats the same way with the palantir-java-format plugin enabled; setup, and why Checkstyle-IDEA's bundled checks must be off, are in README "Code style".
- Frontend: Prettier (`printWidth` 100, single quotes, Angular parser for HTML) and `.editorconfig` (2-space indent). ESLint flat config in `frontend/eslint.config.js` (angular-eslint recommended + template accessibility, `eslint-config-prettier` last so no rule fights Prettier). Component selector prefix `app` (enforced by lint), component styles SCSS. Run `npm run format` and `npm run lint` after editing frontend files.
- Style CI: `.github/workflows/code-style.yml` runs `spotless:check`, `npm run lint`, `npm run format:check` and `scripts/test-code-style.sh` on every push and PR, with Java from `.sdkmanrc` and Node from `frontend/.nvmrc`. It doesn't run tests or builds (ALB-8). The pre-commit hook stays gitleaks-only.
- Docs (ADR 0003): what describes the code lives in the repo and changes in the same PR; planning and product thinking live in Linear, linked, never copied.
  - ADRs in `docs/adr/` for decisions that are costly to reverse (datastore, framework, data model, protocol, cross-cutting pattern), not local design choices. Copy `0000-template.md` to the next free `NNNN-kebab-title.md`, add it to the index in `docs/adr/README.md`. Don't rewrite an accepted ADR: supersede it with a new one.
  - Keep `docs/architecture.md` current: when a PR builds or changes a component or a RAG step, update its diagram and the "Built in" tables.
  - API docs: springdoc (`springdoc-openapi-starter-webmvc-ui`, version in the `springdoc.version` pom property) serves `/v3/api-docs` and `/swagger-ui.html`. Off by default; on in the `dev` profile (`application-dev.properties`), which `./mvnw spring-boot:run` activates through the `spring-boot.run.profiles` pom property. Tests that need them use `@ActiveProfiles("dev")`. API title, description and version are in `config/OpenApiConfig`. Annotate new controllers (`@Tag`, `@Operation`, `@ApiResponse`) so Swagger UI explains them.
  - `.github/workflows/docs.yml` runs `scripts/test-docs.sh` on every push and PR.
- Secrets: real values go in the gitignored root `.env`; `.env.example` holds placeholders only. The gitleaks pre-commit hook (`.githooks/pre-commit`, needs `brew install gitleaks`) blocks staged secrets, and `.github/workflows/gitleaks.yml` runs `scripts/gitleaks-scan-history.sh` (full history, all refs, merge commits included) with a checksum-pinned gitleaks on every push and PR; change the scan there, not in the workflow, so the tests cover it. When bumping gitleaks, update `GITLEAKS_VERSION` and `GITLEAKS_SHA256` there together. False positives go in a `gitleaks:allow` comment or a value-specific `.gitleaks.toml` allowlist, not `.gitleaksignore` fingerprints (too broad) or `--no-verify` (CI would still fail). Never add real-looking tokens to tests or fixtures; build fake ones at runtime as `scripts/test-gitleaks-hook.sh` does.
- Tool versions: bump Java in `.sdkmanrc` and Node in `frontend/.nvmrc`; the code style workflow reads the same files, and the planned build CI (ALB-8) will too. Keep the enforcer range in `backend/pom.xml` and `java.version` on 21 unless moving Java majors on purpose. Bump `.sdkmanrc` for new Temurin 21 patches (security fixes). SDKMAN auto-env reads `.sdkmanrc` only in the directory you `cd` into, so shells must start at the repo root.
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

- Backend wired to the compose Postgres: datasource, pgvector store, `spring-boot-docker-compose` (ALB-10). Ollama stays native on the host.
- CI job for `scripts/test-compose.sh` (ALB-8).
- GitHub Actions build/test CI (ALB-8). The workflows so far are the gitleaks scan, the code style check and the docs check.
- Dependabot (ALB-27).
- Backend `/health` endpoint, CORS and Angular dev proxy (ALB-7).
- Testcontainers integration tests (ALB-16), Playwright E2E (ALB-29), RAG evaluation set (ALB-17).
- Observability: Grafana `otel-lgtm` in Docker Compose, OTLP export, Spring AI observations, Grafana MCP (ALB-18–22); optional Langfuse (ALB-23).
