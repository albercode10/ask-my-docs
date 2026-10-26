# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

ask-my-docs is a RAG ("ask questions about your documents") app, currently at the skeleton stage: no RAG, ingestion or chat code exists yet. It is a monorepo with two independent projects and no root-level build:

- `backend/` — Spring Boot 4.1 + Spring AI 2.0 (`spring-ai-starter-model-ollama`, Spring MVC), Java 21, Maven 3.10 via wrapper. Package root `com.alber.backend`.
- `frontend/` — Angular 22 standalone app (no NgModules, signals, `@angular/build` application builder), SCSS, Vitest for unit tests. Node `^22.22.3 || ^24.15.0 || >=26`.

The backend talks to a **local Ollama** instance (`spring.ai.ollama.base-url=http://localhost:11434`, chat model `qwen3:8b`, in `backend/src/main/resources/application.properties`). Ollama must be running with the model pulled before the backend starts or the context-load test runs:

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

## Conventions

- Lombok is on the annotation-processor path for both main and test compilation.
- Frontend: Prettier (`printWidth` 100, single quotes, Angular parser for HTML) and `.editorconfig` (2-space indent). Component selector prefix `app`, component styles SCSS.
- Ignore rules: repo-wide noise (OS, JetBrains, `.env`, `.claude/settings.local.json`) in the root `.gitignore`; build output in `backend/.gitignore` and `frontend/.gitignore`.
- Remote: `github.com/albercode10/ask-my-docs`, default branch `main`.

## Workflow

[ADD: Linear workflow — team key, project, milestones, cycle cadence, branch naming and `Fixes ALB-N` PR convention. Not verified: the Linear connector was not authenticated when this was written.]

## Planned

[ADD: tooling decided in Linear tickets but not yet in the repo (database, formatter/linters, CI, E2E tests, observability). Not verified: Linear was unavailable.]
