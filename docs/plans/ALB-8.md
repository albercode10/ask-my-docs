# ALB-8: CI — GitHub Actions to build and test backend and frontend

## Goal

Every push and PR runs the backend build and tests, the frontend build and tests, and the Docker Compose smoke tests in GitHub Actions, so PRs show green checks for each. Ticket: https://linear.app/alberto-caro/issue/ALB-8/ci-github-actions-to-build-and-test-backend-and-frontend

## Sources read

- ALB-8 description (no comments, attachments or linked docs). Blocks ALB-27 (Dependabot).
- `docs/plans/ALB-26.md` (code-style workflow, action pins), the three existing workflows, `scripts/test-compose.sh`, `scripts/test-docs.sh` (test-script style), README and CLAUDE.md mentions of ALB-8.

## Decisions made with you

- Triggers: every push, every PR and manual dispatch, like the other workflows (the ticket said "to main").
- A third job runs `scripts/test-compose.sh` (CLAUDE.md assigns it to ALB-8).
- `code-style.yml` stays a separate workflow.
- Node: the ticket says "Node LTS". `frontend/.nvmrc` pins 26.13.0, and Node 26 is the current LTS line, so the job reads `.nvmrc` (CLAUDE.md: CI reads the same version files).

## Project profile

- Monorepo, no root build. `backend/`: Spring Boot 4.1.1, Spring AI 2.0.1, Java 21 (Temurin 21.0.13 in `.sdkmanrc`, enforcer), Maven wrapper. `verify` runs enforcer, tests (`BackendApplicationTests`, `OpenApiDocsTests`, Ollama mocked) and `spotless:check`. `frontend/`: Angular 22.2, Vitest 5 + jsdom via `@angular/build:unit-test` (headless already), Node 26.13.0 (`.nvmrc`), npm 11.19.1.
- Checked locally on `main` (e853397): `./mvnw verify` passes, `npm run build` passes, `ng test --watch=false` passes (2 tests), Docker is running.
- Workflow conventions: actions pinned by commit SHA with a version comment (checkout v7.0.1 `3d3c42e…`, setup-java v6.2.0 `77dd5b2…`, setup-node v7.1.0 `949feb2…`), `permissions: contents: read`, `persist-credentials: false`, header comment pointing to the plan, `-B -ntp` for Maven.
- Repo tooling tests: POSIX `sh` scripts in `scripts/` with `pass`/`fail` counters, `test_*` functions, temp dirs with cleanup trap, self-checks on broken copies (`test-docs.sh`).
- Branch: `albercode10/alb-8-ci-github-actions-to-build-and-test` from up-to-date `main`. PR body `Fixes ALB-8`.

## Approach

1. **`.github/workflows/build.yml`** (`name: build`), on `push`, `pull_request`, `workflow_dispatch`; `permissions: contents: read`; `concurrency` per ref with `cancel-in-progress` so superseded pushes stop. Three independent jobs, so each shows as its own PR check:
   - **backend**: checkout, setup-java (`java-version-file: .sdkmanrc`, `cache: maven`, `cache-dependency-path: backend/pom.xml`), `./mvnw -B -ntp verify` in `backend/`. On failure, upload `backend/target/surefire-reports` as an artifact (pinned `actions/upload-artifact`, short retention).
   - **frontend**: checkout, setup-node (`node-version-file: frontend/.nvmrc`, `cache: npm`, `cache-dependency-path: frontend/package-lock.json`), `NG_CLI_ANALYTICS=false`, `npm ci`, `npm run build`, `npm run test:ci`.
   - **compose**: checkout, `sh scripts/test-compose.sh` (runner has Docker and Compose v2; the script fails rather than skips without a daemon).
   - Same action SHAs as `code-style.yml`; `upload-artifact` gets its SHA looked up from GitHub at implementation time.
2. **`frontend/package.json`**: `"test:ci": "ng test --watch=false"`, so CI and developers run the same one-shot command.
3. **`scripts/test-workflows.sh`**: static checks on all workflow files, plus self-checks on broken copies (like `test-docs.sh`). Runs as a fourth small job, **workflows**, in `build.yml` (needs only `sh`, `grep`, `awk`).
4. **Docs** (below). The header comments of `code-style.yml` stop saying build/test CI is ALB-8.

## Acceptance criteria

1. `build.yml` runs on every push, PR and manual dispatch, with jobs `backend`, `frontend`, `compose` and `workflows`.
2. The backend job uses Java from `.sdkmanrc`, caches Maven and runs `./mvnw verify` (tests + Spotless).
3. The frontend job uses Node from `frontend/.nvmrc`, caches npm and runs `npm ci`, the production build and the unit tests headless, once.
4. The compose job runs `scripts/test-compose.sh`.
5. Every action in every workflow is pinned to a full commit SHA; every workflow sets `permissions: contents: read`; every checkout sets `persist-credentials: false`.
6. On the PR, the four checks are green (verified after you push; I can't push).
7. README and CLAUDE.md describe the build CI and the new commands; nothing still lists it as planned.

## Test cases

`scripts/test-workflows.sh` (red now: no `build.yml`, no `test:ci`):

- `build_runs_on_push_pull_request_and_dispatch`
- `build_has_backend_frontend_compose_workflows_jobs`
- `backend_job_reads_java_from_sdkmanrc_and_caches_maven`
- `backend_job_runs_mvnw_verify`
- `frontend_job_reads_node_from_nvmrc_and_caches_npm`
- `frontend_job_runs_npm_ci_build_and_test_ci`
- `frontend_test_ci_script_disables_watch`
- `compose_job_runs_test_compose_script`
- `workflows_job_runs_test_workflows_script`
- `all_actions_pinned_to_commit_sha` (all workflows)
- `all_workflows_have_read_only_permissions`
- `all_checkouts_disable_persisted_credentials`
- self-checks: `pin_check_fails_on_tag_ref`, `permissions_check_fails_when_missing` (on throwaway copies)

Also: `./mvnw verify`, `npm run build`, `npm run test:ci`, `sh scripts/test-compose.sh` pass locally; `npm run lint`/`format:check` and `scripts/test-docs.sh` still pass. No E2E (no user flow).

Manual check: after you push the branch and open the PR, the `build` workflow shows four green jobs and the cache is saved on the first run (hit on the second).

## Docs to update

- `README.md`: a "CI" section listing the four workflows and what each runs; the `.sdkmanrc`/`.nvmrc` sentence no longer says "will"; `npm run test:ci` and `scripts/test-workflows.sh` under Testing.
- `CLAUDE.md`: commands (`npm run test:ci`, `sh scripts/test-workflows.sh`); Conventions: a build CI line, style CI line no longer points at ALB-8, tool versions line; remove the two ALB-8 items from Planned.
- `docs/architecture.md`: only if it lists CI (it doesn't today; will check).
- `docs/plans/ALB-8.md`: this plan + status.

## Out of scope

- Dependabot (ALB-27), Testcontainers (ALB-16), Playwright (ALB-29).
- Merging `code-style.yml` into `build.yml`; branch protection rules (set in GitHub settings, yours to do).
- actionlint (not installed; could be added later as a CI step).
- Publishing build artifacts (jar, `dist/`) or Docker images.

## Open risks

- The compose job pulls the pinned pgvector image on each run (~150 MB, no cache). Acceptable for now.
- Grep-based workflow checks can't validate YAML semantics; the real check is the green PR run.
- Runs twice per push to a PR branch (push + pull_request), same as the other workflows.

## Review fixes (approved after hand-off)

From the fresh-eyes review, findings 1–6:

1. Maven cache: `code-style.yml` and the backend job got the same setup-java cache key (hash of `backend/pom.xml`), so the faster code-style job saved a Spotless-only cache and the backend job could never save its own. `code-style.yml` now restores with `cache-read-only: true` (setup-java v6.2.0); the backend job is the only writer.
2. `test-workflows.sh` iterates workflow files line by line, so paths with spaces work, and fails when it finds no workflow files.
3. `build.yml` cancels superseded runs only off `main`: every commit on `main` gets a result. On `main` the concurrency group includes the commit SHA, because GitHub keeps one pending run per group and replaces it even without `cancel-in-progress` (found by the re-review).
4. The pin check accepts a quoted `uses:` value; the checkout check skips commented lines.
5. The write-permission check catches flow style (`{ contents: write }`) and quoted values.
6. Every `build.yml` job has `timeout-minutes`.

New tests: `code_style_maven_cache_is_read_only`, `backend_maven_cache_is_writable`, `build_cancels_superseded_runs_only_off_main`, `every_build_job_has_timeout`, `checks_fail_when_no_workflows`, `pin_check_fails_under_path_with_spaces`, `pin_check_accepts_quoted_sha`, `credentials_check_ignores_commented_checkout`, `permissions_check_fails_on_flow_style_write`, `permissions_check_fails_on_quoted_write`, `cancel_check_ignores_commented_line`, `cache_read_only_check_ignores_commented_line`. Checks that look for a fixed line skip comment lines.

## Status

Branch: `albercode10/alb-8-ci-github-actions-to-build-and-test`

- [x] Plan approved
- [x] Red: failing tests (9 of 16 in scripts/test-workflows.sh failed: no build.yml, no test:ci)
- [x] Green: implementation and docs (all local suites pass)
- [x] Manual checks: every job command run locally; the GitHub run waits for the push
- [x] E2E (none planned)
- [x] Independent review (1 major, 4 minor, 3 nit; no blockers)
- [x] Hand-off: findings 1–6 fixed, re-reviewed; its two minor findings fixed, nits left open
