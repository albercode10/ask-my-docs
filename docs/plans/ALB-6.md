# ALB-6: Docker Compose for local dev: Postgres with pgvector

## Goal

`docker compose up` at the repo root gives a local Postgres 18 with the pgvector extension, credentials from `.env`, data in a named volume. It's ready for ALB-10 (pgvector store); the backend doesn't use it yet. Ticket: https://linear.app/alberto-caro/issue/ALB-6/docker-compose-for-local-dev-postgres-with-pgvector

## Sources read

- ALB-6: description, no comments, no attachments, no linked docs. Blocks ALB-10; related ALB-32, ALB-9.
- Docker Hub tags for `pgvector/pgvector`: latest is `0.8.7-pg18-trixie` (2026-10-31).
- Official `postgres` image docs: from PG 18, `PGDATA` is `/var/lib/postgresql/18/docker` and volumes must mount at `/var/lib/postgresql` (not `.../data`).
- Note: the ticket says the README lists `qwen3:8b` and `nomic-embed-text`; the README only lists `qwen3:8b`. Not fixed here (belongs to ALB-32 / ALB-9).

## Decisions made with you

- `spring-boot-docker-compose` is deferred to ALB-10, together with the JDBC/pgvector starter that uses it. No backend change in this ticket.
- Tests are a shell smoke test, `scripts/test-compose.sh`, run locally. Wiring it into CI is left to ALB-8.

## Project profile

- Monorepo: `backend/` (Spring Boot 4.1.1, Spring AI 2.0.1, Java 21) and `frontend/` (Angular 22). No root build. Ollama native on the host.
- Your machine: Docker 29.4.3, Compose v5.1.3, Docker Desktop installed but **not running** (needs starting for the smoke test). Port 5432 free.
- Test style for repo tooling: POSIX `sh` scripts with `pass`/`fail` counters and `test_*` functions, throwaway state in a temp dir, fake secrets built at runtime (`scripts/test-gitleaks-hook.sh`).
- Secrets: `.env` (gitignored) at root; `.env.example` already has `POSTGRES_DB/USER/PASSWORD` placeholders tagged for ALB-6.
- CI: only `.github/workflows/gitleaks.yml`.
- Git: branch `albercode10/alb-6-docker-compose-for-local-dev-postgres-with` from up-to-date `main` (ALB-25 is merged), PR body `Fixes ALB-6`.

## Approach

1. **`compose.yaml`** at the repo root (Compose reads the root `.env` for interpolation automatically):
   - `name: ask-my-docs`; one service `postgres`, image `pgvector/pgvector:0.8.7-pg18-trixie` (exact tag; Dependabot in ALB-27 can bump it).
   - `POSTGRES_DB/USER/PASSWORD` as `${VAR:?...}`, so a missing `.env` fails fast with "set POSTGRES_PASSWORD in .env (copy .env.example)" instead of starting with empty credentials.
   - Port `127.0.0.1:${POSTGRES_PORT:-5432}:5432`: reachable from the host only, not the LAN; port overridable if 5432 is taken.
   - Named volume `postgres-data` at `/var/lib/postgresql` (PG 18 layout).
   - Init script mounted read-only at `/docker-entrypoint-initdb.d`.
   - Healthcheck with `pg_isready`, so `docker compose up --wait` returns once it accepts connections.
2. **`docker/postgres/init/01-create-vector-extension.sql`**: `CREATE EXTENSION IF NOT EXISTS vector;` in the app database, so `vector` is installed, not just available. Runs only on the first start with an empty volume (documented).
3. **`.env.example`**: add an optional, commented `POSTGRES_PORT=5432`; reword the ALB-6 comment.
4. **`scripts/test-compose.sh`**: smoke test in the style of the gitleaks test. Uses a temp env file with runtime-generated fake credentials, a throwaway project name (`-p ask-my-docs-test-<pid>`) and a free port, so it never touches your dev database or `.env`; a `trap` runs `down -v`. Fails with a clear message if the Docker daemon isn't running.
5. **Docs** (below).

No ADR: Postgres + pgvector was already decided in Linear (ALB-6/ALB-10), and this ticket only adds dev tooling.

## Acceptance criteria

1. With `.env` copied from `.env.example` and a password set, `docker compose up -d --wait` at the repo root starts a healthy Postgres 18, and returns only once the real server (not the image's temporary init server) accepts TCP connections.
2. In the app database, `vector` is installed (`pg_extension`), and a `vector(3)` column can be created and queried with `<->`.
3. Data survives `docker compose down` + `up` (named volume); `down -v` removes it, and the next start runs the init script again.
4. Without the credentials, `docker compose config` fails with a message naming the missing variable. `.env.example` leaves `POSTGRES_PASSWORD` empty, so a copied `.env` fails until a password is set.
5. Postgres listens on 127.0.0.1 only; the image is pinned by exact tag and multi-arch index digest (`tag@sha256:…`).
6. README and CLAUDE.md explain how to start, stop, connect and reset the database.
7. The smoke test never falls back to port 5432, and cleans up when interrupted (INT/TERM).

## Test cases (`scripts/test-compose.sh`)

Config (no daemon needed):
- `config_fails_without_postgres_password`
- `config_pins_pgvector_image_tag`
- `config_binds_postgres_to_localhost_only`
- `config_mounts_named_volume_at_pg18_data_root`
- `config_healthcheck_probes_over_tcp` (review fix 1)
- `env_example_fails_config_until_password_set` (review fix 7)
- `env_example_with_password_passes_config` (review fix 5)
- `config_pins_pgvector_image_tag` now also requires `@sha256:<64 hex>` (review fix 6)

Running (Docker daemon needed):
- `up_wait_reports_postgres_healthy`
- `vector_extension_installed_in_app_database`
- `vector_column_supports_distance_query`
- `data_persists_after_down_and_up`
- `down_v_deletes_data_and_reruns_init` (review fix 4)

Not unit-tested (script plumbing, checked by hand): abort when no free port is found (fix 3), INT/TERM cleanup (fix 2).

Red: run before `compose.yaml` exists; all fail because there's no compose file. Review fixes: the new and tightened tests fail before the fix.

Manual check: `cp .env.example .env`, set `POSTGRES_PASSWORD`, `docker compose up -d --wait`, `docker compose exec postgres psql -U askmydocs -d askmydocs -c '\dx vector'`.

## Docs to update

- `README.md`: Docker Desktop or OrbStack in prerequisites; new "Local database" section (start, stop, connect, reset with `down -v`, init script runs only on an empty volume, the backend doesn't use it until ALB-10); smoke test command under Testing.
- `CLAUDE.md`: compose commands and `scripts/test-compose.sh` under Commands; remove "Postgres + pgvector via Docker Compose" from Planned and note that the backend isn't wired to it yet (ALB-10, with `spring-boot-docker-compose`).
- `.env.example`: as above.

## Out of scope

- Backend datasource, JDBC/pgvector starters, `spring-boot-docker-compose` (ALB-10).
- CI job for the smoke test (ALB-8). Ollama in Docker (stays native).
- Fixing the README's missing `nomic-embed-text` line (ALB-32 / ALB-9).

## Open risks

- Docker Desktop must be running for the runtime tests and the manual check; I can't start it for you.
- If you already have a Postgres on 5432, set `POSTGRES_PORT` in `.env`.

## Status

Branch: `albercode10/alb-6-docker-compose-for-local-dev-postgres-with`

- [x] 4. Red: failing smoke test
- [x] 5. Green: compose.yaml, init script, docs
- [x] 6. Manual check
- [x] 7. E2E: none planned (skipped)
- [x] 8. Independent review: findings 1–7 fixed test-first; 8–9 accepted (superuser role → ALB-10)
- [x] 9. Hand-off
