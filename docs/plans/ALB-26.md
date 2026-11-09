# ALB-26: Code style — Spotless for Java, ESLint + Prettier for Angular, enforced in CI

## Goal

Unformatted Java or TypeScript, or an ESLint error, fails CI, and one command fixes formatting locally (`./mvnw spotless:apply`, `npm run format`). Ticket: https://linear.app/alberto-caro/issue/ALB-26/code-style-spotless-for-java-eslint-prettier-for-angular-enforced-in

## Sources read

- ALB-26 description (no comments, no attachments). Related: ALB-8 (build/test CI, not started).
- Spotless Maven plugin README (Context7, `/diffplug/spotless`): `palantirJavaFormat`, `removeUnusedImports`, `trimTrailingWhitespace`, `endWithNewline`, `check` bound to `verify` by default.
- angular-eslint docs (Context7): `ng add angular-eslint`, flat `eslint.config.js`.
- Maven Central: spotless-maven-plugin **3.11.0**, palantir-java-format **2.103.0** (latest). npm: angular-eslint **22.6.0** (peer eslint ^9 || ^10, typescript-eslint ^8), eslint 10.14.0, typescript-eslint 8.72.2 (TS < 6.1, project has 6.0.3), eslint-config-prettier 10.1.8.
- GitHub: actions/setup-java v6.2.0 (`77dd5b2…`, reads `.sdkmanrc` via `java-version-file`, distribution from the `-tem` suffix), actions/setup-node v7.1.0 (`949feb2…`, `node-version-file`, `cache-dependency-path`).

## Decisions made with you

- A new standalone workflow now (`code-style.yml`); ALB-8 can fold it in later.
- Pre-commit hook stays gitleaks-only.
- A committed shell test, `scripts/test-code-style.sh`, like the gitleaks and compose tests.

## Project profile

- Monorepo, no root build. `backend/`: Spring Boot 4.1.1, Spring AI 2.0.1, Java 21 (Temurin 21.0.13, `.sdkmanrc`, enforcer), Maven 3.10 wrapper; pom indented with tabs; two Java files. `frontend/`: Angular 22.2.3, TS 6.0.3, Vitest 5, Prettier 3.8 with `.prettierrc` (printWidth 100, single quotes, Angular HTML parser), Node 26.13.0 (`.nvmrc`), npm 11.19.1. No ESLint yet.
- Today `npx prettier --check .` flags 9 files in `frontend/`.
- CI: only `.github/workflows/gitleaks.yml` (actions pinned by SHA, `permissions: contents: read`, `persist-credentials: false`).
- Tests for repo tooling: POSIX `sh` scripts in `scripts/` with `pass`/`fail` counters and `test_*` functions, throwaway temp dirs, cleanup trap.
- `.idea/` is gitignored, so Checkstyle-IDEA is local IDE config, not repo config.
- Branch: `albercode10/alb-26-code-style-spotless-for-java-eslint` from up-to-date `main`. PR body `Fixes ALB-26`.

## Approach

1. **`backend/pom.xml`**: add `spotless-maven-plugin` 3.11.0 (version in a `spotless.version` property). `<java>` with `palantirJavaFormat` 2.103.0 (default PALANTIR style, 120 columns), `removeUnusedImports`, `trimTrailingWhitespace`, `endWithNewline`. One execution with goal `check` (default phase `verify`). No `ratchetFrom`: everything is formatted once.
2. **Frontend ESLint**: `npx ng add angular-eslint@22` (adds `lint` target, `eslint.config.js`, dev deps). Then add `eslint-config-prettier` as the last entry in `eslint.config.js` so ESLint rules never fight Prettier. Keep the generated rules (app prefix selectors match the project convention).
3. **`frontend/package.json` scripts**: `lint` (`ng lint`), `format` (`prettier --write .`), `format:check` (`prettier --check .`). Prettier already ignores `.gitignore`d paths (`dist`, `node_modules`, `.angular/cache`).
4. **Format everything**: `./mvnw spotless:apply` and `npm run format`, and fix any lint errors in the existing code. At commit time this goes in its own commit ("Format all code with Spotless and Prettier"), before the tooling commit, so later diffs stay clean.
5. **`.github/workflows/code-style.yml`**: on push, PR and manual dispatch; `contents: read`. One job: checkout (same pinned SHA, `persist-credentials: false`), setup-java (`java-version-file: .sdkmanrc`, `cache: maven`), setup-node (`node-version-file: frontend/.nvmrc`, `cache: npm`, `cache-dependency-path: frontend/package-lock.json`), `npm ci`, then `./mvnw -B spotless:check`, `npm run lint`, `npm run format:check`, then `sh scripts/test-code-style.sh` (runs even when a check fails, like the gitleaks workflow). Only `spotless:check`, not `verify`: running tests is ALB-8.
6. **`scripts/test-code-style.sh`**: copies `backend/` (pom, wrapper, src) and `frontend/` (config + src, `node_modules` symlinked) into a temp dir, so the repo is never touched. Uses the local `~/.m2`.
7. **Docs** (below).

## Acceptance criteria

1. `./mvnw spotless:check` and `./mvnw verify` fail on a badly formatted Java file or an unused import, naming the file; `./mvnw spotless:apply` fixes it.
2. `npm run format:check` fails on a badly formatted TS/HTML file; `npm run format` fixes it.
3. `npm run lint` (`ng lint`) fails on an ESLint error (e.g. a component selector without the `app` prefix), and passes on the current code.
4. ESLint and Prettier don't conflict: `eslint-config-prettier`'s check reports no conflicting rules.
5. The whole repo is formatted: all three checks pass on the branch.
6. `code-style.yml` runs the checks and the test script on push and PR, with tool versions from `.sdkmanrc` and `.nvmrc`.
7. README and CLAUDE.md say how to format, lint, and set up IntelliJ.

## Test cases

`scripts/test-code-style.sh` (red now: no Spotless, no lint target, no scripts):

- `backend_check_passes_on_repo`
- `backend_check_fails_on_unformatted_java` (output names the file)
- `backend_check_fails_on_unused_import`
- `backend_verify_runs_spotless_check` (`./mvnw verify -DskipTests` fails on the bad file)
- `backend_apply_fixes_java` (after `spotless:apply`, check passes and the unused import is gone)
- `frontend_format_check_passes_on_repo`
- `frontend_lint_passes_on_repo`
- `frontend_format_check_fails_on_unformatted_ts`
- `frontend_format_fixes_ts`
- `frontend_lint_fails_on_selector_without_app_prefix`
- `eslint_config_has_no_prettier_conflicts` (`npx eslint-config-prettier` on the TS sources)

Assertions check the tool's own message (e.g. Spotless "format violations"), so the red run fails because the tooling is missing, not because a command errors in an unrelated way.

Also: `./mvnw test` and `npm test -- --watch=false` and `npm run build` still pass after the format commit. No E2E (no user flow).

Manual check: in IntelliJ with the palantir-java-format plugin enabled, Reformat Code on a Java file leaves `spotless:check` green.

## Docs to update

- `README.md`: new "Code style" section (`spotless:apply`/`check`, `npm run format`/`format:check`/`lint`, CI workflow, IntelliJ: install and enable palantir-java-format; Checkstyle-IDEA's bundled Sun/Google checks disagree with Palantir style, so turn them off or drop the plugin); test script under Testing.
- `CLAUDE.md`: commands; Conventions (Spotless/Palantir, ESLint + `eslint-config-prettier`, `code-style.yml`, test script); remove the Spotless/ESLint item from Planned and note ALB-8 still owns build/test CI.
- `docs/plans/ALB-26.md`: this plan + status.

## Out of scope

- Build/test CI (ALB-8), Dependabot (ALB-27).
- Formatting files outside `backend/src` and `frontend/` (root YAML, Markdown, shell scripts, pom with sortpom).
- Pre-commit formatting.
- Typed linting (rules needing type information).
- `.git-blame-ignore-revs` for the format commit: possible after merge, since its SHA must be final.

## Open risks

- palantir-java-format on JDK 21 inside Spotless may need `--add-exports` JVM flags (`.mvn/jvm.config`); I'll check on the first run and add them only if needed.
- `ng add` may pick slightly different versions than listed; I'll record what it installs.
  - Installed: angular-eslint 22.6.0 and typescript-eslint 8.71.0 (pinned exactly by `ng add`), eslint ^10.12.0, @eslint/js ^10.0.1, eslint-config-prettier ^10.1.8. Palantir on JDK 21 needed no extra JVM flags.
  - `eslint-config-prettier`'s CLI crashes if `ESLINT_USE_FLAT_CONFIG=true` is set with ESLint 10 (it asks for the removed `FlatESLint`); unset, it uses ESLint 10's flat `ESLint` and works. The test leaves the variable unset; checked by hand that it exits 2 on a conflicting rule.
- The test script runs Maven several times; expect a minute or two.

## Status

Branch: `albercode10/alb-26-code-style-spotless-for-java-eslint`

- [x] 4. Red: failing `scripts/test-code-style.sh` (11/11 fail: no Spotless plugin, no npm scripts, no ESLint)
- [x] 5. Green: Spotless, ESLint, scripts, format all, workflow, docs (11/11 pass on Node 26.13.0 + Temurin 21.0.13; `./mvnw verify`, `ng test`, `npm run build` pass)
- [x] 6. Manual check: CI's three checks pass locally; workflow YAML parses. Not run: the workflow on GitHub (needs a push), IntelliJ plugin (your machine)
- [x] 7. E2E: none planned (skipped)
- [x] 8. Independent review: no blockers or majors; 1 minor (Spotless step skipped when npm ci fails), 3 nits (CLAUDE.md IntelliJ line, JDK build pinned to 21.0.13 not +9, minimatch audit advisory)
- [x] 9. Hand-off: fixed the minor (Spotless step now runs before `npm ci`, and `npm ci` runs even if Spotless fails) and nit 2 (CLAUDE.md points to the README IntelliJ setup). Nits 3–4 accepted as is.
