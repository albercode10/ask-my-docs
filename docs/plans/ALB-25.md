# ALB-25: Pin tool versions (SDKMAN for Java 21, Node version file)

## Goal

Your Mac, CI and anyone who clones the repo use the same Java and Node. Java 21 (Temurin) is pinned with SDKMAN's `.sdkmanrc` and switches automatically on `cd`. Node 26 is pinned with `frontend/.nvmrc`. Ticket: https://linear.app/alberto-caro/issue/ALB-25/pin-tool-versions-sdkman-for-java-21-and-a-node-version-file

## Sources read

- ALB-25: description, no comments, no attachments. Related: ALB-8 (build/test CI, not started), ALB-9 (README).
- SDKMAN candidates API (darwinarm64): latest Temurin 21 is `21.0.13.0+9-tem`. Adoptium API confirms 21.0.13+9 is the latest 21 GA.
- nodejs.org dist index: v26.13.0 "Lithium" (Active LTS, 2026-10-28), v24.22.2 (maintenance LTS).
- Angular 22.2.3 `engines.node`: `^22.22.3 || ^24.15.0 || >=26.0.0`, so 26.13.0 is supported.
- Maven Enforcer docs (Context7, `/apache/maven-enforcer`): `requireJavaVersion` with a Maven range.

## Decisions made with you

- Node 26, exact patch (`26.13.0`).
- CI wiring is deferred to ALB-8. This ticket adds the files CI will read.
- The backend build enforces Java 21 with Maven Enforcer. No npm `engines` and no version-check script.
- You install SDKMAN and set `sdkman_auto_env` yourself. I change repo files only and give you the commands.

## Project profile

- Monorepo: `backend/` (Spring Boot 4.1.1, Spring AI 2.0.1, `java.version` 21, Maven 3.10.0 via wrapper 3.3.4) and `frontend/` (Angular 22.2.3, Vitest 5, npm 11.19.1 via `packageManager`). No root build.
- Tests: `./mvnw test` in `backend/`, `npm test` in `frontend/`. CI: only `.github/workflows/gitleaks.yml`.
- Your machine today: default `java` is OpenJDK 26.0.1 (not Temurin), no SDKMAN. nvm installed, Node v24.22.1.
- Spring Boot's parent manages `maven-enforcer-plugin` 3.6.3, so the pom needs no version.
- Git: branch `albercode10/alb-25-pin-tool-versions-sdkman-for-java-21-and`, PR body `Fixes ALB-25`.

## Approach

1. **`.sdkmanrc`** at the repo root: `java=21.0.13.0+9-tem` (what `sdk env init` writes, with the Temurin 21 identifier). At the root only: SDKMAN's auto-env reads `.sdkmanrc` in the directory you `cd` into, so it switches on entering the repo root (and the selected JDK stays as you `cd` into subfolders), not when a terminal opens directly in `backend/`. The README says to start at the root; no second `.sdkmanrc` in `backend/`, to keep one pin.
2. **`frontend/.nvmrc`**: `26.13.0`. Read by nvm, fnm (`--use-on-cd`) and `actions/setup-node` (`node-version-file`). One file, no `.node-version` duplicate.
3. **`backend/pom.xml`**: add `maven-enforcer-plugin` with an `enforce` execution and `requireJavaVersion` `[21,22)`, with a message pointing to `sdk env`. It runs at `validate`, so `./mvnw` fails fast on any JDK other than 21.
4. **Docs**: README and CLAUDE.md (below).
5. **For ALB-8**: comment on ALB-8 (only when you say so at hand-off) that CI should use `actions/setup-java` with `distribution: temurin` and `java-version: 21.0.13+9` (or `java-version-file: .sdkmanrc`, if supported by the setup-java version then) and `actions/setup-node` with `node-version-file: frontend/.nvmrc`.

## APIs verified

- `maven-enforcer-plugin` 3.6.3 `requireJavaVersion` with range `[21,22)` and `message` (Context7, `/apache/maven-enforcer`).

## Acceptance criteria

1. After the machine setup, `cd` into the repo switches to Temurin 21.0.13, and `java -version` there prints 21.0.13.
2. In `frontend/`, `nvm use` selects Node 26.13.0, and `node -v` prints v26.13.0.
3. `./mvnw validate` fails with a clear message on a JDK that isn't 21 (e.g. your OpenJDK 26), and passes on Java 21.
4. `./mvnw test` passes on Java 21. `npm ci`, `npm test -- --watch=false` and `npm run build` pass on Node 26.13.0.
5. README and CLAUDE.md say how to get the pinned versions, and the files CI should read.

## Test cases

This ticket adds config, not code, so the checks are commands (no new unit tests):

- Red, before the change: `JAVA_HOME=<OpenJDK 26> ./mvnw -q validate` passes, though it should fail.
- Green, after: the same command fails with the enforcer message. `./mvnw validate` and `./mvnw test` pass under Temurin 21.
- `cat .sdkmanrc` / `frontend/.nvmrc` match the versions above. `sdk env` and `nvm use` select them.
- Frontend suite and production build pass on Node 26.13.0.

Manual check: after you install SDKMAN, `cd` out and back into the repo and run `java -version` and `node -v` in `frontend/`.

## Docs to update

- `README.md` Prerequisites: SDKMAN + `sdk env install` + `sdkman_auto_env=true`, Node via `nvm install` in `frontend/` (optional: nvm or fnm auto-switch on `cd`). Note that the backend build refuses non-21 JDKs.
- `CLAUDE.md`: the Project section's Java/Node lines point at `.sdkmanrc` and `frontend/.nvmrc`. Remove "Pinned tool versions" from Planned. Add a convention: bump versions in these files, and keep the enforcer range on Java 21.

## Out of scope

- Build/test CI (ALB-8). Dependabot for version bumps (ALB-27).
- npm `engines` / `engine-strict`, and a version-consistency script (you didn't pick them).
- Installing SDKMAN, Temurin 21 or Node 26 on your Mac (you run the commands).
- IntelliJ project SDK (`.idea/` is gitignored).

## Open risks

- Dependabot (ALB-27) doesn't update `.sdkmanrc`, so Temurin 21 patch bumps (security fixes) are manual. Older pins keep installing: `sdk list java` shows only the newest patch, but SDKMAN still downloads older identifiers (checked: `21.0.12.1+1-tem`, `21.0.9-tem`).
- The enforcer stops the backend build on your machine until Java 21 is selected. That's intended. IntelliJ must also run Maven with a Java 21 JDK.
- Node 26 bundles its own npm, while `packageManager` says npm 11.19.1. I'll check that `npm ci` works on 26.13.0 and report it.

## Status

Branch: `albercode10/alb-25-pin-tool-versions-sdkman-for-java-21-and`

- [x] 4. Red: `./mvnw -q validate` passes on OpenJDK 26.0.1 (exit 0), though it should fail
- [x] 5. Green: version files, enforcer, docs. On OpenJDK 26 `./mvnw validate` fails with the enforcer message; on Temurin 21.0.12.1 (installed locally) validate and `./mvnw test` pass. On Node 26.13.0 (npm 11.20.0): `npm ci`, `ng test --watch=false` (2 tests) and `npm run build` pass, lockfile unchanged
- [x] 6. Manual checks: no endpoints, so no `.http` files. On the developer's machine: SDKMAN installer needs Bash 4+ (macOS has 3.2; worked with `brew install bash`). `sdk env install` gave Temurin 21.0.13+9 (also set as SDKMAN default); `nvm install` in `frontend/` gave v26.13.0 / npm 11.20.0. With `sdkman_auto_env=true` and OpenJDK 26.0.1 as SDKMAN default, in a new shell: `cd ~` gives 26.0.1, `cd` into the repo root gives 21.0.13, `backend/` stays on 21.0.13 and `./mvnw -q validate` passes, `cd ~` restores 26.0.1. Acceptance criterion 1 met
- [x] 7. E2E: none planned
- [x] 8. Independent review: 0 blockers, 0 majors; 3 minors, 2 nits (see hand-off)
- [x] 9. Hand-off. Developer chose to fix the 3 MINOR review findings: auto-env only at the repo root (document it), the false "old pins stop installing" claim, and README's present-tense "CI uses". Fixed in README.md, CLAUDE.md and this plan (docs only, no code change)
