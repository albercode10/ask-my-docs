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

The backend context-load test needs Ollama running.

## Project tracking

[ADD: Linear team, project and milestones — not verified when this README was written.]

## Contributing with Claude Code

See [CLAUDE.md](CLAUDE.md) for commands, conventions and architecture notes aimed at Claude Code.
