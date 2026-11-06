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

- [gitleaks](https://github.com/gitleaks/gitleaks) for the pre-commit secret scan (`brew install gitleaks`)

After cloning, turn on the repo's git hooks once:

```bash
git config core.hooksPath .githooks
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

## Secrets

Secrets (database password, Grafana token, API keys) never go in the repo:

- Put them in `.env` at the repo root, which is gitignored. Copy [`.env.example`](.env.example) to start; it holds placeholders only.
- The pre-commit hook ([`.githooks/pre-commit`](.githooks/pre-commit)) runs `gitleaks git --staged` and blocks a commit that contains a secret. It also blocks commits when gitleaks isn't installed.
- CI ([`.github/workflows/gitleaks.yml`](.github/workflows/gitleaks.yml)) runs [`scripts/gitleaks-scan-history.sh`](scripts/gitleaks-scan-history.sh) with a checksum-verified gitleaks on every push and pull request. It scans the full history: all branches and tags, merged branches, and anything added inside a merge commit. It fails on a finding.
- GitHub secret scanning and push protection are on for the repository.

**If the hook flags a real secret:** unstage it, move it to `.env`, and rotate it if it was ever shared.

**If CI flags a secret:** it is already on GitHub, so rotate it first. Then remove it from history: delete the branch if it isn't merged, or rewrite it (`git rebase -i` to drop or edit the commit) and force-push. Because CI scans every branch, one leaked secret on any branch fails CI on all branches until it's gone.

**False positives:** add a `gitleaks:allow` comment at the end of the line. It works locally and in CI, on every branch. For a file that can't hold a comment (such as JSON), add a value-specific allowlist to a `.gitleaks.toml` at the repo root (regexes or stopwords that match that exact value). Start that file with `[extend]` / `useDefault = true`, or it replaces gitleaks' built-in rules instead of adding to them. Avoid `.gitleaksignore` fingerprints: for staged changes they ignore that line of that file for good, so a real secret added there later would pass too. They also only apply to branches that contain the file. `git commit --no-verify` only skips the local hook: CI still scans the commit and fails.

Test the hook and the CI scan with the command below. CI runs it too, after the scan.

```bash
sh scripts/test-gitleaks-hook.sh
```

## Project tracking

Work is planned in Linear (team `ALB`, project `ask-my-docs`) in 2-week cycles, across four milestones:

1. **Foundations** — repo hygiene, local dev setup, backend–frontend wiring
2. **RAG MVP** — document ingestion, embeddings, pgvector store, ask endpoint with cited sources
3. **Chat UI** — upload and chat pages with streamed answers and sources
4. **Polish** — integration and E2E tests, answer-quality evaluation, observability, demo

Branches use the Linear branch name of their issue, and PRs reference it with `Fixes ALB-<N>`.

## Contributing with Claude Code

See [CLAUDE.md](CLAUDE.md) for commands, conventions and architecture notes aimed at Claude Code.
