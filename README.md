# ask-my-docs

Ask questions about your documents and get answers grounded in them, using a local LLM.

> **Status:** early. The backend answers questions about one book at `POST /chat`, sending the whole book to the model with each question. Retrieval (RAG), uploads, streaming and the chat UI are **planned** and not built yet.

## Architecture

| Part | Stack | Role |
| --- | --- | --- |
| `backend/` | Spring Boot 4.1, Spring AI 2.0, Java 21, Maven | REST API; talks to the LLM through Spring AI's Ollama starter |
| `frontend/` | Angular 22 (standalone), Vitest | Web UI |
| Ollama (local) | `qwen3:8b` at `http://localhost:11434` | Chat model, configured in `backend/src/main/resources/application.properties` (32k context, thinking off) |
| Postgres (Docker) | Postgres 18 + pgvector, [`compose.yaml`](compose.yaml) | Vector store for the RAG pipeline; not used by the backend yet (ALB-10) |

How the parts fit together and how a question becomes an answer: [docs/architecture.md](docs/architecture.md).

## Prerequisites

- Java 21 (Temurin), pinned in [`.sdkmanrc`](.sdkmanrc). Maven comes with the wrapper, `./mvnw`. The backend build fails on any other Java version.
- Node 26, pinned in [`frontend/.nvmrc`](frontend/.nvmrc), with the npm it bundles
- [Ollama](https://ollama.com) running locally, with the model pulled:

  ```bash
  ollama pull qwen3:8b
  ```

- The book's EPUB in `data/books/` (see [The book](#the-book)). Without it the backend still starts, but `/chat` answers 503.
- [gitleaks](https://github.com/gitleaks/gitleaks) for the pre-commit secret scan (`brew install gitleaks`)
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) or [OrbStack](https://orbstack.dev), for the local Postgres. Ollama stays outside Docker: Docker on macOS can't use the Apple GPU.

After cloning, turn on the repo's git hooks once:

```bash
git config core.hooksPath .githooks
```

### Java and Node versions

The repo pins its Java and Node versions in files that the build and code style CI also read. Java comes from [SDKMAN](https://sdkman.io). Install it once:

```bash
curl -s "https://get.sdkman.io" | bash
```

Turn on auto-switching, so `cd` into the repo selects the pinned JDK: set `sdkman_auto_env=true` in `~/.sdkman/etc/config`, then open a new terminal. SDKMAN only looks for `.sdkmanrc` in the directory you `cd` into, so enter the repo at its root; the JDK stays selected in subfolders. A terminal opened directly in `backend/` keeps your default JDK: run `cd ..` and come back, or `sdk env` from the root. Then, at the repo root, install the pinned JDK:

```bash
sdk env install
```

Node comes from [nvm](https://github.com/nvm-sh/nvm) (or [fnm](https://github.com/Schniz/fnm), which reads the same file). In `frontend/`:

```bash
nvm install
```

Run `nvm use` in `frontend/` in each new terminal, or set up nvm's [deeper shell integration](https://github.com/nvm-sh/nvm#deeper-shell-integration) (or `fnm env --use-on-cd`) to switch on `cd`.

To bump a version, change `.sdkmanrc` or `frontend/.nvmrc`. Bump `.sdkmanrc` when a new Temurin 21 patch comes out, to pick up its security fixes. [Dependabot](#dependency-updates) doesn't read these files.

## Local database

Postgres 18 with the [pgvector](https://github.com/pgvector/pgvector) extension runs in Docker Compose. The backend doesn't use it yet: it's ready for the pgvector store (ALB-10). Start Docker Desktop or OrbStack, then at the repo root:

```bash
cp .env.example .env
```

Set `POSTGRES_PASSWORD` in `.env` (Compose refuses to start while it's empty), then start it. `--wait` returns once Postgres accepts connections:

```bash
docker compose up -d --wait
```

Postgres listens on `localhost:5432` only (set `POSTGRES_PORT` in `.env` if that port is taken). Check that pgvector is installed:

```bash
docker compose exec postgres psql -U askmydocs -d askmydocs -c '\dx vector'
```

Stop it with `docker compose down`; the data stays in the `postgres-data` volume. To start from scratch, run `docker compose down -v`, which deletes the data. Do that too after changing the credentials in `.env`: they're only applied when the volume is first created, and so is [`docker/postgres/init/`](docker/postgres/init), which installs the `vector` extension.

## The book

The app answers questions about one book: *Gentleman's Guide to Beard and Moustache Management* by Chris Martin (EPUB). The book is copyrighted, so it's **never committed**: `data/` is git-ignored. Put your copy here, under this name:

```text
data/books/beard-and-moustache-management.epub
```

The backend reads it at startup and logs `Loaded book …: N characters`. To use another path, set `app.book.path` (for example `APP_BOOK_PATH=/path/to/book.epub ./mvnw spring-boot:run`). A relative path resolves against `backend/`. If the file is missing or can't be read, the backend logs a warning, still starts, and `POST /chat` answers 503.

The whole book (about 21k tokens) goes to the model with every question, so Ollama runs `qwen3:8b` with a 32k context window. That needs roughly 5 GB of memory on top of the model. Processing the book is slow (about 4.5 minutes measured on the development Mac), so the backend does it once in the background right after startup (`app.chat.warm-up`, logged as `Warmed up the model with the book in N s`), and Ollama keeps it in its prompt cache. After that, a question only pays for itself: about 2 s for a short answer, longer for a long one (generation runs at roughly 25 characters per second). The cache is lost when Ollama unloads the model, after 30 minutes idle (`spring.ai.ollama.chat.keep-alive`), so the first question after that is slow again.

## Running

Backend:

```bash
cd backend
./mvnw spring-boot:run
```

The backend listens on `127.0.0.1:8080` only (`server.address`): there's no authentication yet, so other machines on the network can't use the local model or pull the book through `/chat`. It also answers only requests addressed to `localhost`, `127.0.0.1` or `[::1]`, and gives 403 to any other `Host`, so a web page in your browser can't reach it by pointing its own domain at 127.0.0.1 (DNS rebinding).

`POST /chat` takes a question of at most 2,000 characters (longer gives 400), caps the answer at about 1,000 tokens (`spring.ai.ollama.chat.num-predict`), and answers 503 when the book isn't loaded or the model doesn't answer (Ollama down, the model not pulled, or an empty answer). Model calls aren't retried for long (`spring.ai.retry.*`), so that 503 comes within seconds. Ask a question from the [Bruno collection](#trying-the-api-with-bruno) (**Chat → Valid question**), or from the terminal:

```bash
curl -s localhost:8080/chat -H 'Content-Type: application/json' -d '{"question": "How do I trim a moustache?"}'
```

Frontend (served at http://localhost:4200):

```bash
cd frontend
npm install
npm start
```

### From IntelliJ IDEA

Every command in this README is also a shared run configuration in [`.run/`](.run), so IntelliJ lists them in the Run widget after you open the repo root as the project. They're grouped in folders:

| Folder | Configurations |
| --- | --- |
| Setup (once) | git hooks, `ollama pull qwen3:8b`, frontend `npm ci` |
| Run | **Run app (backend + frontend)**, Backend: run (dev), Frontend: start, Postgres: up / down |
| Backend | test, verify (what CI runs), spotless apply, package |
| Frontend | test (watch), test:ci, build, lint, format, format:check |
| Scripts | the `scripts/*.sh` checks: docs, workflows, compose, code style, gitleaks hook, gitleaks history scan |

Settings they rely on:

- **Maven** (Settings → Build Tools → Maven): *Maven home path* set to **Use Maven wrapper**, and the runner JRE set to the project JDK, Temurin 21. Any other Java fails the build.
- **Node** (Settings → Languages & Frameworks → Node.js): the interpreter from [`frontend/.nvmrc`](frontend/.nvmrc).
- The shell configurations (Setup, Postgres and Scripts) run in IntelliJ's terminal with `/bin/sh` from the repo root. The Maven and npm ones run in the Run tool window. Postgres needs the `.env` from [Local database](#local-database).
- The backend runs from `backend/`, so the default book path `../data/books/…` resolves as it does on the command line.

### Trying the API with Bruno

Every endpoint has requests in a [Bruno](https://www.usebruno.com) collection, [`backend/bruno/`](backend/bruno). Bruno is a free, open-source desktop API client (no account), and the collection is plain YAML files in the repo (Bruno 4, OpenCollection format).

1. Install Bruno once: `brew install --cask bruno`.
2. In Bruno: **Open Collection** and choose the `backend/bruno` folder.
3. Pick the **Local** environment (top right; it's the default), which points `{{baseUrl}}` at `http://localhost:8080`.
4. Start the backend, open a request (for example **Chat → Valid question**) and press **Send** (⌘↵).

The response panel shows the status, the body and the time. Each request also carries checks (the expected status, plus tests such as "answer is not blank"), shown as passed or failed in the **Tests** tab. Requests that call the model have no timeout, since a long answer can take a minute.

| Folder | Requests |
| --- | --- |
| Chat | Valid question, Question the book doesn't cover, Blank question, Missing question, Empty body, Question too long (2,001 characters), Question at the limit (2,000 characters), Request for another host |
| API docs | OpenAPI document, Swagger UI |

## Testing

```bash
cd backend && ./mvnw test
```

```bash
cd frontend && npm test
```

`npm test` watches for changes; `npm run test:ci` runs the tests once, as CI does.

The tests don't need Ollama or the book: they stub the model and read a small EPUB they generate. Ollama and the book are only needed to answer real chat requests.

Smoke tests for the local database. They need Docker running, and use a throwaway Compose project, port and credentials, so your database and `.env` are left alone:

```bash
sh scripts/test-compose.sh
```

Tests for the code style tooling below. They run the checks on throwaway copies of `backend/` and `frontend/` (run `npm ci` in `frontend/` first):

```bash
sh scripts/test-code-style.sh
```

Checks for the docs below: ADR numbering, titles, statuses and sections, the ADR index, the architecture diagrams, and every relative link in the Markdown files. CI runs them ([`docs.yml`](.github/workflows/docs.yml)):

```bash
sh scripts/test-docs.sh
```

Checks for the CI workflows below: what the build workflow runs and with which versions, and that every workflow pins its actions to a commit SHA, has a read-only token and doesn't persist credentials. They also check the [Dependabot config](#dependency-updates). CI runs them:

```bash
sh scripts/test-workflows.sh
```

## CI

GitHub Actions runs four workflows on every push and pull request. Each job shows as its own check on the PR:

| Workflow | Jobs | What runs |
|---|---|---|
| [`build.yml`](.github/workflows/build.yml) | `backend` | `./mvnw verify`: compile, tests, Spotless |
| | `frontend` | `npm ci`, `npm run build` (production), `npm run test:ci` |
| | `compose` | `sh scripts/test-compose.sh` |
| | `workflows` | `sh scripts/test-workflows.sh` |
| [`code-style.yml`](.github/workflows/code-style.yml) | `code style` | Spotless, ESLint, Prettier, `scripts/test-code-style.sh` ([Code style](#code-style)) |
| [`docs.yml`](.github/workflows/docs.yml) | `docs` | `scripts/test-docs.sh` |
| [`gitleaks.yml`](.github/workflows/gitleaks.yml) | `gitleaks` | full-history secret scan ([Secrets](#secrets)) |

Java and Node come from `.sdkmanrc` and `frontend/.nvmrc`, and Maven and npm downloads are cached. The Maven cache is shared: only the `backend` job saves it (when it succeeds), and `code-style.yml` only restores it, so a saved cache holds the full dependency set. A newer push to the same branch cancels the build run it replaces, except on `main`, where every commit gets a result. Each job has a timeout (5–20 minutes). When the `backend` job fails, its test reports are attached to the run as the `surefire-reports` artifact. Actions are pinned to commit SHAs; when bumping one, update the SHA and its version comment in every workflow that uses it.

## Dependency updates

[Dependabot](.github/dependabot.yml) checks for new versions every Monday and opens PRs, labelled `dependencies` plus an area label. CI runs on them like on any other PR.

| Ecosystem | Directory | Grouped minor and patch PRs |
|---|---|---|
| Maven | `backend/` | `spring` (Spring Boot, Spring AI), `maven` (the rest) |
| npm | `frontend/` | `angular` (Angular, its CLI and builder, angular-eslint, TypeScript), `npm` (the rest) |
| GitHub Actions | `.github/workflows/` | `actions` (SHA pins and their version comments) |
| Docker Compose | `compose.yaml` | `docker` (image tag and digest) |

- Major updates come as one PR each, since they usually need code changes.
- A new release waits 7 days before Dependabot proposes it (majors 14 days for Maven and npm), so a broken or yanked release has time to be caught. At most 3 version PRs per ecosystem are open at once.
- Security alerts and security update PRs are repo settings, not part of the config file: turn them on under Settings → Advanced Security (they're on for this repo; a fork starts with them off). Security PRs skip the wait and the PR limit. The Compose image gets version updates only: Dependabot has no security updates for Docker Compose.
- Not covered, bump by hand: Java (`.sdkmanrc`), Node (`frontend/.nvmrc`), npm (`packageManager` in `frontend/package.json`), the Maven wrapper, gitleaks (`GITLEAKS_VERSION` and `GITLEAKS_SHA256` in [`gitleaks.yml`](.github/workflows/gitleaks.yml)) and `palantir-java-format.version` in `backend/pom.xml` (used only inside the Spotless plugin's config).
- Dependabot reads its config from `main` only, so a change to it takes effect after merge. Its labels must exist in the repo, or Dependabot leaves them off.

## Documentation

Docs that describe the code live in the repo and change in the same PR as the code. Planning and product thinking live in Linear ([ADR 0003](docs/adr/0003-linear-for-tracking-repo-and-linear-docs-for-documentation.md)).

- [Architecture](docs/architecture.md): components and the RAG flow, with diagrams.
- [Architecture decision records](docs/adr/README.md): why the stack is what it is, and how to add an ADR.
- API: with the backend running, Swagger UI is at http://localhost:8080/swagger-ui.html and the OpenAPI document at http://localhost:8080/v3/api-docs. Endpoints document themselves with springdoc annotations as they're added. Both are on only in the `dev` profile, which `./mvnw spring-boot:run` activates ([`application-dev.properties`](backend/src/main/resources/application-dev.properties)); a packaged jar run without it doesn't publish them.
- [`backend/bruno/`](backend/bruno): the [Bruno](https://www.usebruno.com) collection to try the API by hand, with a check on every request (see [Trying the API with Bruno](#trying-the-api-with-bruno)).
- [Plans](docs/plans): the approved plan for each ticket.

## Code style

CI ([`code-style.yml`](.github/workflows/code-style.yml)) fails on unformatted code or ESLint errors, on every push and pull request. One command fixes formatting.

Backend: [Spotless](https://github.com/diffplug/spotless) with [Palantir Java Format](https://github.com/palantir/palantir-java-format) (4-space indent, 120 columns). It also removes unused imports, trims trailing whitespace and ends files with a newline. In `backend/`:

```bash
./mvnw spotless:apply
```

`./mvnw spotless:check` only checks; `./mvnw verify` runs it too.

Frontend: [Prettier](https://prettier.io) formats (config in [`frontend/.prettierrc`](frontend/.prettierrc)), [ESLint](https://eslint.org) with [angular-eslint](https://github.com/angular-eslint/angular-eslint) lints (config in [`frontend/eslint.config.js`](frontend/eslint.config.js)). `eslint-config-prettier` turns off the ESLint rules that would fight Prettier. In `frontend/`:

```bash
npm run format
```

`npm run format:check` only checks. Lint with:

```bash
npm run lint
```

IntelliJ IDEA: install the **palantir-java-format** plugin and enable it in Settings → palantir-java-format, so Reformat Code matches Spotless. If you use Checkstyle-IDEA, turn off its bundled Sun and Google checks: they expect a different style and would flag Palantir-formatted code.

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
