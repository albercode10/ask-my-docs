# ask-my-docs

Ask questions about your documents and get answers grounded in them, using a local LLM.

> **Status:** project skeleton. The backend and frontend boot, but document ingestion, retrieval (RAG) and the chat UI are **planned** and not built yet.

## Architecture

| Part | Stack | Role |
| --- | --- | --- |
| `backend/` | Spring Boot 4.1, Spring AI 2.0, Java 21, Maven | REST API; talks to the LLM through Spring AI's Ollama starter |
| `frontend/` | Angular 22 (standalone), Vitest | Web UI |
| Ollama (local) | `qwen3:8b` at `http://localhost:11434` | Chat model, configured in `backend/src/main/resources/application.properties` |

## Prerequisites

- Java 21 (Maven comes with the wrapper, `./mvnw`)
- Node `^22.22.3 || ^24.15.0 || >=26` and npm
- [Ollama](https://ollama.com) running locally, with the model pulled:

  ```bash
  ollama pull qwen3:8b
  ```

## Running

Backend:

```bash
cd backend
./mvnw spring-boot:run
```

Frontend (served at http://localhost:4200):

```bash
cd frontend
npm install
npm start
```

## Testing

```bash
cd backend && ./mvnw test
```

```bash
cd frontend && npm test
```

The tests don't need Ollama: the backend context starts without it. Ollama is only needed to answer chat requests.

## Project tracking

Work is planned in Linear (team `ALB`, project `ask-my-docs`) in 2-week cycles, across four milestones:

1. **Foundations** — repo hygiene, local dev setup, backend–frontend wiring
2. **RAG MVP** — document ingestion, embeddings, pgvector store, ask endpoint with cited sources
3. **Chat UI** — upload and chat pages with streamed answers and sources
4. **Polish** — integration and E2E tests, answer-quality evaluation, observability, demo

Branches use the Linear branch name of their issue, and PRs reference it with `Fixes ALB-<N>`.

## Contributing with Claude Code

See [CLAUDE.md](CLAUDE.md) for commands, conventions and architecture notes aimed at Claude Code.
