# ALB-24: Documentation setup — docs as code in the repo, Linear Docs for planning

## Goal

The repo gets its technical docs (ADRs, an architecture page with a Mermaid diagram, Swagger UI from springdoc), all linked from the README, and the Linear project gets two planning docs. Ticket: https://linear.app/alberto-caro/issue/ALB-24/documentation-setup-docs-as-code-in-the-repo-and-linear-docs-for

## Sources read

- ALB-24 description (no comments, no attachments). Related: ALB-32 (in-memory RAG, Todo), ALB-10 (pgvector stretch, Todo), ALB-30 (`/chat`, Todo), ALB-7 (health endpoint, **Canceled**), ALB-9.
- Linear project doc "Roadmap: weeks and milestones" (Week 2 order, one book, no `/api` prefix, in-memory store first).
- springdoc FAQ (Context7 `/websites/springdoc`): 3.x is for Spring Boot 4; `springdoc.swagger-ui.path`; global `OpenAPI` bean for info.
- Maven Central: springdoc-openapi **3.2.0** (2026-10-24) builds on Spring Boot **4.1.2** (project: 4.1.1, same minor); 3.1.1 builds on 4.1.0.

## Decisions made with you

- springdoc goes in now with no operations: Swagger UI loads at `/swagger-ui.html` with the API title and description. ALB-30 and later endpoints annotate themselves. No `/health` here.
- ADR 0004 (Grafana LGTM) is written with status **Proposed**: the observability tickets are canceled for now.
- The two Linear docs are drafted and shown at hand-off, and created in Linear only after you approve the text.

## Project profile

- Monorepo, no root build. `backend/`: Spring Boot 4.1.1, Spring AI 2.0.1, Java 21, Maven wrapper, pom indented with tabs, Spotless/Palantir. Only `BackendApplication` + `BackendApplicationTests` (`@SpringBootTest`, JUnit 5). `spring-boot-starter-webmvc-test` is on the test path. No controllers, no `backend/http/` yet.
- Repo tooling tests are POSIX `sh` scripts in `scripts/` (`pass`/`fail` counters, `test_*` functions, cleanup trap); CI workflows pin actions by SHA, `contents: read`, `persist-credentials: false`.
- No `docs/adr/` yet; plans in `docs/plans/`.
- Branch: `albercode10/alb-24-documentation-setup-docs-as-code-in-the`, from an up-to-date `main` (ALB-26 is merged). PR body `Fixes ALB-24`.

## Approach

1. **ADRs, `docs/adr/`**
   - `README.md`: what an ADR is here, when to write one (costly-to-reverse decisions only), numbering (`NNNN-kebab-title.md`, 4 digits, next free number, never reused), statuses (Proposed / Accepted / Deprecated / Superseded by NNNN), and an index table of all ADRs.
   - `0000-template.md`: `# NNNN. Title`, `Date`, `Status`, then `## Context`, `## Options considered`, `## Decision`, `## Consequences`.
   - `0001-spring-ai-and-ollama-as-llm-stack.md` (Accepted): Spring AI 2.0 + Ollama native on the host, `qwen3:8b`; vs hosted APIs and LangChain4j; Apple GPU reason for native Ollama; consequence: tests stub `ChatModel`.
   - `0002-in-memory-vector-store-then-pgvector.md` (Accepted): `SimpleVectorStore` first (ALB-32), pgvector in the compose Postgres later (ALB-10); embeddings `nomic-embed-text` (768 dims, so the pgvector table must match).
   - `0003-linear-for-tracking-repo-and-linear-docs-for-documentation.md` (Accepted): Linear team ALB; the split rule "describes how the code works → repo; planning or product thinking → Linear"; link, don't copy.
   - `0004-grafana-lgtm-for-observability.md` (Proposed): `grafana/otel-lgtm` in Compose, OTLP export, Spring AI observations; not scheduled.
   - ADRs link the Linear tickets for background rather than copying them.
2. **`docs/architecture.md`**: components (Angular, Spring Boot, Ollama native, Postgres+pgvector in Docker) with a Mermaid component diagram, and the RAG flow as Mermaid: indexing (book EPUB at startup → read → chunk → embed → vector store) and question (question → embed → retrieve top-k → prompt → `qwen3:8b` → answer + sources). A "Status" note per part says what's built and what's planned (with ticket), so the page stays honest while the code catches up. Uploads (ALB-11) shown as a later entry point. Links to ADRs 0001/0002.
3. **springdoc**: `backend/pom.xml` gets `springdoc-openapi-starter-webmvc-ui` with a `springdoc.version` property (3.2.0). New `com.alber.backend.config.OpenApiConfig` with an `OpenAPI` bean (title "ask-my-docs API", description). `application.properties`: `springdoc.swagger-ui.path=/swagger-ui.html` (the default, set explicitly so the documented URL is pinned). After review: the API docs are off by default and on in the `dev` profile (`application-dev.properties`), which `./mvnw spring-boot:run` activates through the Spring Boot Maven plugin, so a packaged jar doesn't publish them.
4. **`backend/pom.xml` metadata**: fill `<name>` (`ask-my-docs backend`) and `<description>`. The other empty Initializr elements (`url`, `licenses`, `developers`, `scm`) stay as they are.
5. **Manual-check files**: `backend/http/http-client.env.json` (`dev.baseUrl` = `http://localhost:8080`) and `backend/http/api-docs.http` (GET `/v3/api-docs` with assertions on status and title; GET `/swagger-ui.html`).
6. **`scripts/test-docs.sh`** (tests below) and a small `.github/workflows/docs.yml` that runs it on push/PR (no Java or Node needed).
7. **Docs** (below).

## Acceptance criteria

1. `docs/adr/` has the template, the README with numbering convention and index, and ADRs 0001–0004, each with a title matching its number, a date, a valid status, and Context / Decision / Consequences sections.
2. `docs/architecture.md` describes the components and the RAG flow with Mermaid diagrams that render on GitHub.
3. With the backend running through `./mvnw spring-boot:run` (`dev` profile), `/swagger-ui.html` loads Swagger UI and `/v3/api-docs` returns the OpenAPI document titled "ask-my-docs API". Without the `dev` profile, both answer 404. `./mvnw test` still passes without Ollama.
4. `backend/pom.xml` has a non-empty name and description.
5. The README links the architecture page, the ADR index and Swagger UI; every relative link in README, CLAUDE.md and `docs/` resolves.
6. CI runs `scripts/test-docs.sh` on push and PR.
7. Draft texts for the Linear docs "Product spec / MVP scope" and "Ideas & notes" are presented at hand-off; created in Linear only on your OK.

## Test cases

Backend, `OpenApiDocsTests` (`@SpringBootTest` + MockMvc, no Ollama):
- `apiDocsAreServedWithTheApiTitleAndDescription` (GET `/v3/api-docs` → 200, JSON `info.title` = "ask-my-docs API", description present, `info.version` = `v0`: added during the manual check, since a custom `Info` drops springdoc's default version, which the OpenAPI spec requires)
- `swaggerUiHtmlRedirectsToTheSwaggerUi` (GET `/swagger-ui.html` → 3xx to `/swagger-ui/index.html`)
- `swaggerUiIndexIsServed` (GET `/swagger-ui/index.html` → 200, HTML)
- These three run with `@ActiveProfiles("dev")`. `ApiDocsDisabledByDefaultTests`: `apiDocsAreNotServedWithoutTheDevProfile`, `swaggerUiIsNotServedWithoutTheDevProfile` (404).

`scripts/test-docs.sh`:
- `adr_template_has_required_sections`
- `adr_files_are_numbered_without_gaps_or_duplicates`
- `each_adr_title_matches_its_number`
- `each_adr_has_date_and_valid_status`
- `each_adr_has_context_decision_consequences`
- `adr_index_lists_every_adr`
- `architecture_has_mermaid_diagrams`
- `readme_links_architecture_adrs_and_swagger_ui`
- `relative_markdown_links_resolve` (README.md, CLAUDE.md, docs/**/*.md): inline links (titles, `<…>` targets, parentheses in targets), reference definitions, HTML `href`/`src`; `/root` paths and `?query` handled; `#anchors` checked against the target file's headings (GitHub slugs); links in fenced blocks and inline code ignored
- `pom_has_name_and_description`
- Self-checks on throwaway copies, so the checks are known to fail when they should: a gap in ADR numbering, a missing Consequences section, an unknown status, and a broken relative link each make the script report FAIL. Added after review: template without its real Status line; index row whose status disagrees with the ADR while another row mentions it; broken anchor; broken reference definition; broken HTML link; parentheses and `<…>` targets resolve; inline-code links ignored; all checks pass on a copy under a path with a space.

Manual: `.http` requests against a running backend; Swagger UI in the browser; Mermaid diagrams on the GitHub branch view after push. No E2E (no user flow).

## Docs to update

- `README.md`: new "Documentation" section (architecture, ADRs, Swagger UI URL, plans); Architecture table links the architecture page; `scripts/test-docs.sh` under Testing.
- `CLAUDE.md`: Conventions: ADRs in `docs/adr/` (when and how), architecture page kept current with the code, springdoc annotations on new controllers, `scripts/test-docs.sh` and `docs.yml`; commands; remove the docs-as-code item from Planned.
- `docs/plans/ALB-24.md`: this plan + status.

## Out of scope

- A `/health` endpoint (ALB-7 is canceled); any controller annotations (none exist).
- Other empty Initializr elements in the pom.
- Stale items elsewhere in README/CLAUDE.md that this ticket doesn't touch (e.g. old milestone names, canceled tickets still in CLAUDE.md "Planned"): flagged at hand-off, not fixed here.
- Mermaid syntax checking in CI (would need a headless browser via mermaid-cli); checked by hand on GitHub.

## Open risks

- springdoc 3.2.0 builds on Boot 4.1.2 vs our 4.1.1: same minor, expected fine; the backend tests confirm it.
- MockMvc serving the swagger-ui webjar: if it doesn't, the index test switches to a random-port test with `RestTestClient`.
- ~~Swagger UI is always on~~: gated behind the `dev` profile after review.

## Status

Branch: `albercode10/alb-24-documentation-setup-docs-as-code-in-the`

- [x] 4. Red: `scripts/test-docs.sh` (7 failing) and `OpenApiDocsTests` (3 failing, 404)
- [x] 5. Green: ADRs, architecture page, springdoc, pom metadata, docs (also: `docs.yml`, `backend/http/`)
- [x] 6. Manual check: running backend served `/v3/api-docs` (title, description), `/swagger-ui.html` → 302 → `/swagger-ui/index.html` 200, Swagger UI rendered "No operations defined in spec!". Mermaid on GitHub pending a push.
- [x] 7. E2E: none planned (skipped)
- [x] 8. Independent review: no blockers; 4 MINOR + 1 NIT (correctness), 1 MINOR (security). all six fixed after review (see "Review fixes" below)
- [ ] 9. Hand-off (incl. Linear doc drafts). Review fixes done: backend 6/6, docs 25/25 on macOS and Ubuntu (dash, mawk); dev profile serves docs, packaged jar 404s; the 3 Mermaid diagrams render with mermaid 11.4.1

## Review fixes

Chosen by the developer: fix all.

1. Template Status check passed vacuously → comment reworded; check matches `^Status: Proposed$` and `^Date: YYYY-MM-DD$`.
2. Link checker gaps → awk extractor for inline, reference and HTML links; anchors checked; inline code ignored.
3. Index status matched any row → only the ADR's own row, status column compared exactly.
4. Mermaid `direction LR` ignored across linked subgraphs → RAG flow split into two `flowchart LR` diagrams (indexing, asking).
5. Paths with spaces → `while read` loops, quoted paths.
6. API docs always on → off by default, on in the `dev` profile that `spring-boot:run` activates.

Re-review of the fixes: all six resolved; 2 MINOR + 6 NIT left. The developer chose to fix both MINORs; the NITs stay as they are.

7. A misnamed ADR file (e.g. `05-new-decision.md`) was skipped by every ADR check → every `.md` in `docs/adr/` except `README.md` and `0000-template.md` counts as an ADR, so a bad name fails the numbering check. Self-check: `numbering_check_fails_on_a_misnamed_adr`.
8. `docs/architecture.md` didn't say the API docs need the `dev` profile → status note and Backend row say so.
