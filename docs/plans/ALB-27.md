# ALB-27: Automated dependency updates with Dependabot

## Goal

Dependabot opens grouped, weekly PRs for the backend (Maven), the frontend (npm), the GitHub Actions pins and the Compose image, and the existing CI runs on them. Ticket: https://linear.app/alberto-caro/issue/ALB-27/automated-dependency-updates-with-dependabot

## Sources read

- ALB-27 description (no comments, attachments or linked docs). It was blocked by ALB-8, which is merged (`main` = add9815).
- `docs/plans/ALB-8.md`, `ALB-25.md`, `ALB-6.md` (their notes on ALB-27), `build.yml`, `scripts/test-workflows.sh`, `scripts/test-compose.sh`, `compose.yaml`, `backend/pom.xml`, `frontend/package.json`, README "Testing" and "CI".
- GitHub docs: Dependabot options reference, supported ecosystems, cooldown tutorial, docker-compose GA changelog (Feb 2025).
- Repo state, read via `gh`: public repo; Dependabot security updates **disabled**; vulnerability alerts **off** (404); no `dependencies` label yet.

## Project profile

- Monorepo, no root build. `backend/`: Spring Boot 4.1.1 (parent), Spring AI 2.0.1 (BOM via `spring-ai.version` property), springdoc 3.2.0, Spotless 3.11.0 + Palantir 2.103.0, Lombok; Maven 3.10.0 via wrapper 3.3.4; Java 21 (`.sdkmanrc`). `frontend/`: Angular 22.2, TypeScript ~6.0, angular-eslint 22.6.0, typescript-eslint 8.71.0, Vitest 5, npm 11.19.1, Node 26 (`.nvmrc`).
- CI: `build.yml` (backend, frontend, compose, workflows), `code-style.yml`, `docs.yml`, `gitleaks.yml`, all on every push and PR, so they run on Dependabot branches and PRs with no change. Actions pinned to full SHAs with a `# vX.Y.Z` comment.
- `compose.yaml` pins `pgvector/pgvector:0.8.7-pg18-trixie@sha256:…`; `scripts/test-compose.sh` already requires a `-pg18` tag plus digest, so a bump that leaves Postgres 18 fails CI.
- Repo tooling tests: POSIX `sh` scripts with `run_test`, `pass`/`fail`, `expect_lines`, block extractors (awk) and self-checks on broken copies (`scripts/test-workflows.sh`).
- Branch: `albercode10/alb-27-automated-dependency-updates-with` (Linear's name) from `main`. PR body `Fixes ALB-27`.

## Approach

1. **`.github/dependabot.yml`** (`version: 2`), header comment pointing to this plan. Four `updates` entries:

   | Ecosystem | Directory | Groups (version updates) |
   |---|---|---|
   | `maven` | `/backend` | `spring` (`org.springframework*`), then `maven` (`*`) |
   | `npm` | `/frontend` | `angular` (`@angular/*`, `@angular-devkit/*`, `@schematics/*`, `angular-eslint`, `typescript`), then `npm` (`*`) |
   | `github-actions` | `/` | `actions` (`*`) |
   | `docker-compose` | `/` | `docker` (`*`) |

   - Every group has `update-types: [minor, patch]`. A dependency goes to the first group it matches, so the `*` group catches the rest. Majors come as one PR each, since they usually need code changes. TypeScript sits in the `angular` group because Angular pins the TS range it supports.
   - Each entry: `schedule: interval: weekly, day: monday, time: "06:00"` (UTC), `open-pull-requests-limit: 3`, `labels: [dependencies]` plus an area label (`backend`, `frontend`, `ci`, `docker`), and `cooldown: default-days: 7`, so a release has a week to be yanked or flagged before it's proposed (supply-chain caution, like the SHA pins). Security updates skip the cooldown and the PR limit.
   - `docker-compose` is used, not `docker`: the image lives in `compose.yaml`, not a Dockerfile. The otel-lgtm image (ALB-18) will be picked up from the same file when it lands.
2. **Tests in `scripts/test-workflows.sh`** (it already checks the CI config and runs in the `workflows` job). A new section with an awk extractor for each `- package-ecosystem:` entry, plus one self-check on a broken copy. No new CI job.
3. **Repo settings, run by me with `gh` once you approve this plan** (they change GitHub, not files):
   - `gh api -X PUT repos/albercode10/ask-my-docs/vulnerability-alerts` (Dependabot alerts, as the ticket asks)
   - `gh api -X PUT repos/albercode10/ask-my-docs/automated-security-fixes` (Dependabot security update PRs; not in the ticket, but alerts without them only notify)
   - `gh label create` for `dependencies`, `backend`, `frontend`, `ci`, `docker`. Dependabot ignores labels that don't exist.
   If you'd rather click these yourself, say so and I'll list them at hand-off instead.
4. **Docs** (below).

## APIs verified (GitHub docs, current)

- `docker-compose` ecosystem value (GA Feb 2025, "similar to Docker").
- `cooldown` (`default-days`, 1–90; default is 3 days). `semver-*-days` are supported for maven and npm but **not** for github-actions or docker-compose, so only `default-days` is used everywhere. Version updates only.
- `groups` with `patterns`, `update-types`; first matching group wins. `open-pull-requests-limit` (default 5) applies to version updates only. Missing custom labels are ignored.
- **Not confirmed in the docs** (see risks): digest + tag updates in Compose, the `-pg18-trixie` suffix handling, and rewriting the `# vX.Y.Z` comment next to a SHA pin.

## Acceptance criteria

1. `.github/dependabot.yml` covers `maven` in `/backend`, `npm` in `/frontend`, `github-actions` in `/` and `docker-compose` in `/`, and each directory holds the manifest Dependabot reads.
2. Every ecosystem runs weekly and groups its minor and patch updates; Spring and Angular each have their own group.
3. Every ecosystem labels its PRs `dependencies` (plus an area label), limits open version PRs to 3 and waits 7 days after a release.
4. Dependabot alerts and security updates are on, and the labels exist (checked with `gh api`).
5. `sh scripts/test-workflows.sh` checks all of the above and runs in CI.
6. After merge to `main` (Dependabot reads the config only from the default branch): Dependabot opens grouped PRs and the CI checks run on them. Verified by you after merge; I can't verify it from the branch.
7. README and CLAUDE.md describe the setup; nothing still lists Dependabot as planned.

## Test cases

`scripts/test-workflows.sh`, red now (no `dependabot.yml`):

- `dependabot_config_is_version_2`
- `dependabot_covers_maven_npm_actions_and_compose`
- `dependabot_directories_hold_their_manifests`
- `every_dependabot_ecosystem_runs_weekly`
- `every_dependabot_group_takes_minor_and_patch_only`
- `spring_and_angular_have_their_own_groups`
- `every_dependabot_ecosystem_has_catch_all_group`
- `every_dependabot_ecosystem_labels_dependencies`
- `every_dependabot_ecosystem_limits_open_prs`
- `every_dependabot_ecosystem_has_cooldown`
- self-check: `ecosystem_check_fails_when_one_is_missing` (throwaway copy)

Also: the existing workflow checks, `scripts/test-docs.sh` and `scripts/test-compose.sh` still pass. No backend/frontend code changes, no E2E.

Manual check: after merge, GitHub → Insights → Dependency graph → Dependabot shows the four ecosystems with no config errors, and the first Monday run opens grouped PRs with green checks. Before merge, `gh api` confirms the settings.

## Docs to update

- `README.md`: a "Dependency updates" section (what Dependabot covers, schedule, groups, cooldown, what it doesn't cover: `.sdkmanrc`, `.nvmrc`, the Maven wrapper); the `test-workflows.sh` sentence mentions Dependabot.
- `CLAUDE.md`: a Conventions line for Dependabot (config file, groups, what stays manual); remove "Dependabot (ALB-27)" from Planned.
- `docs/plans/ALB-27.md`: this plan + status.

## Out of scope

- Java (`.sdkmanrc`), Node (`.nvmrc`), npm (`packageManager`) and the Maven wrapper: Dependabot doesn't read them; they stay manual (as ALB-25 noted).
- Auto-merge of Dependabot PRs; branch protection rules.
- Ignoring specific majors (e.g. Spring Boot 5): revisit when one shows up.
- Renovate (not chosen in the ticket).

## Open risks

- Dependabot's handling of the pinned Compose image (tag suffix `-pg18-trixie` plus digest) isn't documented. If it proposes pg19 or drops the digest, `scripts/test-compose.sh` fails the PR, so nothing slips through; we'd add an `ignore` rule then.
- Same for the `# vX.Y.Z` comment on SHA-pinned actions: if Dependabot updates the SHA but not the comment, the comment goes stale. Visible in the first actions PR.
- Each Dependabot PR runs CI twice (push + pull_request), like every other branch.
- These are grep/awk checks, not a schema validation; the real check is GitHub's config parser after merge.

## Review fixes (approved after hand-off: all findings)

1. Docs and the config header list gitleaks (`gitleaks.yml`) and `palantir-java-format.version` as bumped by hand.
2. Checks run per update entry, not per ecosystem name; the ecosystem check requires exactly the four entries, once each, all starting with `package-ecosystem`.
3. One self-check per Dependabot check (`break_dependabot`: a broken copy, which must differ from the original, makes the named check fail).
4. Docs say alerts and security update PRs are repo settings (Settings → Advanced Security), not versioned, and that Docker Compose gets no security PRs.
5. Nits: entries iterated without word splitting; block lists at the key's indent are read; groups printed with `|` (no tab merging); the ecosystem check reports every problem; the npm check names `package.json` and the lockfile; the `angular` group check covers `@angular-devkit/*`, `@schematics/*`, angular-eslint and TypeScript; maven and npm majors wait 14 days (`semver-major-days`, checked).
6. From the re-review: the manifest check no longer globs against the working directory (it passed only when run from the repo root); `exit` inside pipeline groups replaced (ksh aborted); the catch-all check also fails on `*` in an earlier group; the key-indent labels self-check outdents every item. Left open (nits): unquoted `*`/`@` items and `time:` aren't flagged; `day: monday` and the area labels aren't checked; flow-style maps aren't parsed (they fail closed).

## Status

Branch: `albercode10/alb-27-automated-dependency-updates-with`

- [x] Plan approved
- [x] Red: failing tests (12 of 40 in scripts/test-workflows.sh failed: no .github/dependabot.yml)
- [x] Green: implementation, repo settings and docs (test-workflows 40/40, test-docs 25/25; alerts, security updates and 5 labels set via gh)
- [x] Manual checks: config parses as YAML (Ruby), each check fails on a broken variant, settings confirmed with gh api; the Dependabot run waits for merge to main
- [x] E2E (none planned)
- [x] Independent review (correctness + security: no blockers or majors; 4 minor, 8 nits)
- [x] Hand-off: all review findings fixed (below), re-reviewed; its 1 minor + 3 nits fixed, rest left open
