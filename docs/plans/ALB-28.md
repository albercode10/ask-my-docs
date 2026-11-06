# ALB-28: Secret leak prevention (gitleaks pre-commit hook and CI scan)

## Goal

Stop secrets from reaching GitHub: block them at commit time with a gitleaks pre-commit hook, and fail CI if one slips through. Ticket: https://linear.app/alberto-caro/issue/ALB-28/secret-leak-prevention-gitleaks-pre-commit-hook-and-ci-scan

## Sources read

- ALB-28 (description, no comments, no attachments). Related: ALB-8 (CI, not started: no `.github/` exists yet), ALB-5.
- gitleaks docs via Context7 (`/gitleaks/gitleaks`) and the `gitleaks/gitleaks-action` README (v3.0.0).
- Repo settings via `gh api`: repo is **public, personal account**; secret scanning and push protection are **already enabled**.

## Project profile

- Monorepo, no root build: `backend/` (Spring Boot 4.1, Java 21, `./mvnw test`), `frontend/` (Angular 22, `npm test`).
- No CI, no hooks, no `scripts/`, no `docs/` yet. Root `.gitignore` already ignores `.env`, `.env.*` and un-ignores `.env.example`.
- Tools on host: Homebrew (gitleaks 8.30.1 stable available, not installed); no `pre-commit`, `pipx` or `uv`.
- Branch: `albercode10/alb-28-secret-leak-prevention-gitleaks-pre`. PRs use `Fixes ALB-28`. No commit/push until asked.

## Approach

1. **Install gitleaks** on the host: `brew install gitleaks` (8.30.1).
2. **Versioned plain git hook** `.githooks/pre-commit` (POSIX `sh`), activated per clone with `git config core.hooksPath .githooks`.
   - Runs `gitleaks git --staged --redact --no-banner --verbose --exit-code 99`. Exit 99 (leak; chosen so it can't clash with Go's exit 2 on a panic) blocks the commit with a "possible secret" message that points false positives to a `gitleaks:allow` comment, or a value-specific allowlist in `.gitleaks.toml` (not `.gitleaksignore` fingerprints, which for staged scans silence that file+line for good, and not `--no-verify`, which CI would still flag). Any other non-zero exit (gitleaks itself failed, e.g. a broken `.gitleaks.toml`) blocks with a separate "gitleaks failed" message.
   - **Fails closed** if `gitleaks` isn't installed, with the install hint.
   - Why not the `pre-commit` framework: it needs Python tooling the repo doesn't use (no pipx/uv here) for a single hook; a plain hook is one file, no extra dependency. Easy to migrate later if more hooks arrive (ALB-26 linters could use the same `.githooks/` dir).
3. **CI workflow** `.github/workflows/gitleaks.yml`, standalone (ALB-8 can add its build workflow next to it):
   - Triggers: `push` (all branches), `pull_request`, `workflow_dispatch`.
   - `actions/checkout@<sha> # v7.0.1` with `fetch-depth: 0`, then download the gitleaks 8.30.1 linux_x64 release (same version as local), **verify its sha256** against the value pinned in the workflow, and run `scripts/gitleaks-scan-history.sh` with it. That script runs `gitleaks git --redact --verbose --no-banner --log-opts="--full-history --all --diff-filter=tuxdb --diff-merges=first-parent"`, so every run scans the **full history** of all fetched refs: commits on merged side branches, and content a merge commit adds itself (conflict resolutions, evil merges). The tests run the same script, so CI and tests can't drift apart.
   - *Changed after re-review (2026-11-06):* plain `gitleaks git` (no `--log-opts`) shows no diff for merge commits, so a secret added inside a merge passed CI. `--diff-merges=first-parent` fixes it (`--cc` doesn't; `-m` double-reports). Also `persist-credentials: false` on checkout.
   - *Changed after review (2026-11-06):* the first version used `gitleaks/gitleaks-action@v3`, but on push/PR that action only scans the event's commit range with `--no-merges --first-parent` (and only the first 30 commits of a PR), so secrets on merged side branches or in long fork PRs passed. It also downloaded gitleaks without a checksum. Running the CLI directly fixes all three; the cost is losing the action's PR comments (the job log and the failed check still show the redacted finding).
   - Least-privilege `permissions: contents: read`. Checkout pinned by commit SHA, with `persist-credentials: false`.
   - A second step runs `scripts/test-gitleaks-hook.sh` with the downloaded gitleaks on `PATH`, so a change that breaks the hook or the scan script fails CI. It runs even when the scan step fails (`if: ${{ !cancelled() }}`), so both results show. *Added after the test-coverage check (2026-11-06); was out of scope before.*
   - CI reads `.gitleaksignore` / `.gitleaks.toml` from the checked-out commit but scans every branch, so an allowlist on one branch doesn't help the others, and one leaked secret on any branch fails CI on all of them. README documents this and what to do after a CI finding (rotate, then delete or rewrite the branch).
4. **`.env.example`** at the repo root with placeholder values for the secrets the ticket names (Postgres DB user/password, Grafana token), clearly fake (`change-me`), plus a header comment: copy to `.env`, never commit `.env`.
5. **Full-history scan once**: `gitleaks git` over all 7 commits; report the result at hand-off (and fix/allowlist if anything turns up, after asking).
6. **GitHub secret scanning + push protection**: already on, verified via API. Nothing to change; note it in README.

## Acceptance criteria

1. With the hook active, committing a staged file containing a fake token is **blocked** (non-zero exit, commit not created), and the secret is redacted in the output.
2. A commit with no secrets goes through.
3. If gitleaks isn't installed, the hook blocks the commit and says how to install it.
4. The CI workflow scans the full git history (all refs, merge side-branches included, and content added inside merge commits) with a checksum-verified gitleaks 8.30.1 on every push and PR, and fails when a leak is present.
8. When gitleaks itself fails (not a leak), the hook blocks the commit with a message that says so, instead of reporting a secret.
9. The hook and docs steer false positives to a `gitleaks:allow` comment (works on every branch, locally and in CI) or a value-specific `.gitleaks.toml` allowlist, never to `.gitleaksignore` fingerprints; `--no-verify` is described only as a local bypass that CI still flags.
10. README says what to do when CI (not the hook) finds a secret.
5. `.env` is gitignored; `.env.example` is committed with placeholder values and passes the scan.
6. A full-history `gitleaks git` scan of the repo reports no leaks (or findings are reported to the developer).
7. README and CLAUDE.md describe the setup.

## Test cases

Automated (shell, `scripts/test-gitleaks-hook.sh`, run from the repo root; builds a throwaway git repo in a temp dir, so it never touches this repo's history; the fake token is assembled at runtime so the script itself doesn't trip gitleaks):
- `blocks_commit_with_fake_github_token`
- `redacts_secret_in_hook_output`
- `allows_commit_without_secrets`
- `blocks_commit_when_gitleaks_missing` (runs the hook with a `PATH` that lacks gitleaks)
- `env_example_has_no_secrets` (`gitleaks dir .env.example` exits 0)
- `env_file_is_gitignored` (`git check-ignore .env` in this repo)
- `block_message_points_to_gitleaks_allow` (added after review)
- `reports_gitleaks_error_separately` (malformed `.gitleaks.toml` in the temp repo; added after review)
- `full_history_scan_finds_secret_in_merged_side_branch` (runs `scripts/gitleaks-scan-history.sh`, the CI command, on a temp repo where the secret was committed on a branch merged with `--no-ff`; added after review)
- `full_history_scan_finds_secret_added_in_merge_commit` (secret added while making the merge, committed with `--no-verify`; added after re-review)
- `full_history_scan_passes_clean_history` (the scan script exits 0 on a clean repo with a merge; added after re-review)
- `block_message_points_to_gitleaks_allow` also asserts the message doesn't suggest `.gitleaksignore` fingerprints (re-review)
- `hook_allows_line_marked_gitleaks_allow` (added after the test-coverage check)
- `full_history_scan_honours_gitleaks_allow` (added after the test-coverage check)
- `full_history_scan_redacts_secret` (added after the test-coverage check)
- The whole script also runs under `dash` locally, as Ubuntu's `/bin/sh` does in CI.

Manual checks:
- `gitleaks git` on this repo (full history) → no leaks.
- CI: after the developer pushes the branch, the `gitleaks` workflow passes on the PR. Optional (only if the developer wants): push a throwaway branch with a fake token to see CI fail, then delete it. Not done by me.

E2E: none (no user flow).

## Docs to update

- `README.md`: gitleaks in Prerequisites; one-time `git config core.hooksPath .githooks`; short "Secrets" section (`.env` / `.env.example`, hook, CI scan, GitHub push protection, what to do on a finding).
- `CLAUDE.md`: hook setup and hook test command under Commands; remove gitleaks from "Planned" (ALB-28 now in repo); mention `.github/workflows/gitleaks.yml`.
- Plan file `docs/plans/ALB-28.md` (created after approval).

## Out of scope

- Build/test CI (ALB-8), Dependabot (ALB-27), linters (ALB-26).
- A custom `.gitleaks.toml` (default rules are enough for now).
- Changing GitHub repo settings (already enabled).

## Open risks

- The hook is opt-in per clone (`core.hooksPath`); a fresh clone without that step has no local protection. CI and GitHub push protection are the backstop.
- gitleaks default rules may not catch generic low-entropy passwords (e.g. a plain DB password in a properties file); push protection covers provider tokens only. Acceptable for now; a custom rule can come later.
- I can't prove "CI fails on a leak" without pushing a leaky commit; the action's fail-on-finding behavior is documented and the same gitleaks version is tested locally.

## Status

Branch: `albercode10/alb-28-secret-leak-prevention-gitleaks-pre`

- [x] 3. Plan approved, branch created
- [x] 4. Red: failing hook tests (4 of 6 failed before the hook existed)
- [x] 5. Green: hook, CI workflow, `.env.example`, docs (6/6 pass)
- [x] 6. Manual checks: `gitleaks git` 7 commits, no leaks; `gitleaks dir .` no leaks; hook enabled in this clone
- [x] 7. E2E: none planned
- [x] 8. Independent review: correctness + security. Developer chose to fix findings 1–4 (5 covered by 1): CI now runs the gitleaks CLI over full history with a checksum; hook separates leak vs error; false-positive advice fixed. 9/9 tests pass. Re-review: developer chose to fix all 5 (merge-commit diffs via --diff-merges=first-parent in scripts/gitleaks-scan-history.sh, allowlist advice, CI-finding docs, exit code 99, persist-credentials: false). 11/11 tests pass. Coverage check: added gitleaks:allow (hook + scan) and scan-redaction tests, verified by mutation; test script now runs in CI. 14/14 pass under sh and dash.
- [ ] 9. Hand-off
