# ALB-30: First /chat endpoint, answers about the book with ChatClient and qwen3:8b

## Goal

`POST /chat` answers one question about the beard-and-moustache book. It sends the whole book as the system prompt to `qwen3:8b` on local Ollama through Spring AI `ChatClient`, and returns one complete answer. No retrieval yet. Ticket: https://linear.app/alberto-caro/issue/ALB-30/first-chat-endpoint-answer-questions-about-the-book-with-chatclient

## Sources read

- ALB-30 description and agent notes (no comments, attachments or linked docs). Blocks ALB-31 (streaming) and ALB-32 (in-memory RAG).
- `docs/architecture.md`, README outline, CLAUDE.md, `docs/plans/ALB-8.md` (plan format), backend `pom.xml`, `application*.properties`, the 3 existing test classes, `OpenApiConfig`.
- Spring AI 2.0.1 docs (Context7, `/spring-projects/spring-ai/v2.0.1`): Ollama thinking mode, chat properties, `TikaDocumentReader`. Property names checked against `spring-configuration-metadata.json` in the local `spring-ai-autoconfigure-model-ollama-2.0.1.jar`.

## Project profile

- Monorepo. `backend/`: Spring Boot 4.1.1, Spring AI 2.0.1 BOM, `spring-ai-starter-model-ollama`, `spring-boot-starter-webmvc`, springdoc 3.2.0, Lombok, Java 21, Maven wrapper. Package root `com.alber.backend`. Today it has only `BackendApplication` and `config/OpenApiConfig`.
- Tests: JUnit 5, `@SpringBootTest` + `@AutoConfigureMockMvc` + `MockMvcTester` with AssertJ (`assertThat(mvc.get().uri(...)).hasStatusOk()...`). One class per behaviour, with a Javadoc line on top. Ollama is never called: stub `ChatModel` with `@MockitoBean`.
- CI: `build.yml` backend job runs `./mvnw -B -ntp verify` (tests + `spotless:check`). `docs.yml` runs `scripts/test-docs.sh` (ADR index, diagrams, relative links).
- Manual checks: no `backend/http/` folder yet. IntelliJ HTTP Client with `{{baseUrl}}` from `http-client.env.json`.
- Branch: `albercode10/alb-30-first-chat-endpoint-answer-questions` (Linear's `gitBranchName`), from an up-to-date `main`. The PR says `Fixes ALB-30`.

## Approach

New packages `book` and `chat` under `com.alber.backend`.

1. **Dependencies (`pom.xml`)**: `spring-ai-tika-document-reader` (version from the BOM) and `spring-boot-starter-validation` (for `@NotBlank`).
2. **Config (`application.properties`)**:
   - `app.book.path=../data/books/beard-and-moustache-management.epub`. The path is relative to `backend/`, where `./mvnw spring-boot:run` runs. It can be overridden with an env var or `-D`.
   - `spring.ai.ollama.chat.num-ctx=32768` (checked: flat `num-ctx` in 2.0.1, default 2048).
   - `spring.ai.ollama.chat.think=false` (checked: enum `OllamaThinkProperties` TRUE/FALSE/HIGH/MEDIUM/LOW. `options.think-option` is deprecated in favour of it).
   - `spring.ai.ollama.chat.keep-alive=30m` (checked: String). The default is 5m. Unloading the model drops Ollama's prompt cache, so after 5 idle minutes the next question would process the whole book again. 30m rather than "forever", because the model plus a 32k context holds roughly 10 GB.
   - `app.chat.warm-up=true` (see step 6b).
3. **`book/BookProperties`**: a `@ConfigurationProperties("app.book")` record with `Path path`.
4. **`book/BookLoader`**: reads the EPUB once at startup (changed while implementing, see below), joins the documents' text, cleans it (collapses runs of blank lines and trailing spaces), and logs `Loaded book <file>: N characters`. A missing or unreadable file logs a WARN with the path and how to fix it, and the book counts as absent. The app still starts.
5. **`book/BookContext`** (interface): `Optional<String> contextFor(String question)`, meaning "the book text to give the model for this question". Empty means no book. **`book/FullBookContext`** returns the whole book and ignores the question. ALB-32 replaces this bean with retrieval, and the endpoint stays the same.
6. **`chat/ChatService`**: builds one `ChatClient` from the auto-configured `ChatClient.Builder`. Each request runs `contextFor(question)`, or throws `BookUnavailableException` when there's no book. It then calls `chatClient.prompt().system(s -> s.text(TEMPLATE).param("book", text)).user(question).call().content()`. The system text is the fixed instructions first, then the book, so every request starts with the same tokens and Ollama can reuse its prompt cache. The instructions say to answer only from the book, and to say plainly when the book doesn't cover the question. As a second guard after `think=false`, it strips any `<think>…</think>` block from the answer.
6b. **`chat/ChatWarmUp`** (the cache answer to the slow first request): on `ApplicationReadyEvent`, when `app.chat.warm-up` is true and the book is loaded, it sends one request in the background (on a virtual thread). The request uses the same system prompt as `ChatService` (same template, so the prefix is byte-identical), a fixed short user message, and `OllamaChatOptions` `numPredict(1)`. Ollama processes the book once, and the first real question hits its prompt cache. It doesn't change `num-ctx`, since a different `num-ctx` would reload the model and drop the cache. It logs the duration. Any failure (Ollama down, model not pulled) gives one WARN and nothing else. Tests turn it off with `backend/src/test/resources/config/application.properties` (`app.chat.warm-up=false`). Spring Boot loads that file on top of the main `application.properties` instead of replacing it. The warm-up tests turn it back on.
7. **`chat/ChatController`**: `POST /chat`. It takes the record `ChatRequest(@NotBlank String question)` and returns the record `ChatAnswer(String answer)`, 200 JSON. The `ChatAnswer` name avoids a clash with Spring AI's `ChatResponse`. It is annotated with `@Tag`, `@Operation` and `@ApiResponse` for 200/400/503.
8. **`chat/ChatExceptionHandler`** (`@RestControllerAdvice`): maps `BookUnavailableException` to a 503 `ProblemDetail` with the detail "The book isn't loaded. See README → The book.". Errors for a blank question, a missing field or a missing body stay 400 (Spring's default `@Valid` / unreadable-body handling, returned as `ProblemDetail` via `spring.mvc.problemdetails.enabled=true`).
9. **`backend/http/`**: `http-client.env.json` (`dev.baseUrl=http://localhost:8080`) and `chat.http` with a valid question (asserts 200 and a non-blank `answer`) and a blank one (asserts 400). It also carries a note that the first request is slow and that 32k context needs about 5 GB of extra memory.
10. **`.gitignore`**: `/data/` at the repo root.

**Change while implementing (behavior unchanged):** `TikaDocumentReader` fails on the real book. Tika's auto-detected `EpubParser` reads chapters as strict XML, and this book's chapters are plain HTML (an unclosed `<link>`), so it throws `TIKA-237 … must be terminated by the matching end-tag`. `BookLoader` uses Tika's `EpubParser` directly instead, with `setContentParser` set to the lenient `JSoupParser` (wrapped so it gets a mark/reset stream). The `spring-ai-tika-document-reader` dependency stays: it brings the Tika parsers, and ALB-11 will use `TikaDocumentReader` for uploads. The cleanup also drops the image file names Tika lists for the illustrations, and indentation. The fixture EPUB now has HTML chapters and an illustration, like the real book. Checked on the real book (counts only): 93,291 characters and 16,018 words before cleanup, with no markup, CSS or entities.

No ADR. Sending the whole book is a temporary step: ALB-32 replaces it, and ADR 0002 already covers the vector store.

## APIs verified (Spring AI 2.0.1)

- `spring.ai.ollama.chat.num-ctx` (Integer) and `spring.ai.ollama.chat.think` (enum, `false`), from the jar's configuration metadata.
- `TikaDocumentReader(Resource).read()` and the `spring-ai-tika-document-reader` artifact, from the 2.0.1 ETL docs.
- `AssistantMessage(String)` public constructor (javap on `spring-ai-model-2.0.1`), for the stubbed `ChatModel` responses.
- To check while writing the tests: that the Tika parsers on the classpath detect EPUB (`application/epub+zip`). If they don't, I add the needed Tika parser module, and that becomes a plan change.

## Acceptance criteria

1. The book location is set by `app.book.path`. `data/` is git-ignored, and no book file is in git.
2. Startup loads the book and logs its character count. A missing file logs a warning, and the app still starts.
3. `POST /chat {"question": "..."}` returns 200 `{"answer": "..."}` from `qwen3:8b`, grounded in the book, with no `<think>` text.
4. A blank or missing question returns 400. With no book loaded, the endpoint returns 503 with a short message.
5. The system prompt carries the answer-only-from-the-book instructions and the full book text. Ollama runs with `num-ctx` 32768, thinking off and a 30m keep-alive.
5b. At startup, a background warm-up loads the book into Ollama's prompt cache, so the first question is fast. If the warm-up fails, startup still succeeds.
6. Tests cover all of the above with a generated fixture EPUB and a `@MockitoBean ChatModel`. `./mvnw verify` passes without Ollama.
7. `backend/http/chat.http` has the valid and blank requests, using `{{baseUrl}}`.
8. README, CLAUDE.md and `docs/architecture.md` say where the book goes and what `/chat` does.

## Test cases

Fixture: `book/TestEpubs.write(Path, chapters…)` builds a small EPUB 2 at test time with `ZipOutputStream`: an uncompressed `mimetype` entry first, `container.xml`, an OPF, and 2 XHTML chapters with made-up text containing marker phrases. The real book is never used. Test classes set `app.book.path` to the fixture through `@DynamicPropertySource`.

Unit / slice:
- `BookLoaderTests`
  - `loadsTheTextOfEveryChapterFromAnEpub`
  - `cleanedTextHasNoMarkupOrRunsOfBlankLines`
  - `missingFileGivesNoBookInsteadOfFailing`

Integration (`@SpringBootTest` + `MockMvcTester` + `@MockitoBean ChatModel`):
- `ChatEndpointTests`
  - `validQuestionReturns200WithTheModelsAnswer`
  - `blankQuestionReturns400AndDoesNotCallTheModel`
  - `missingQuestionReturns400`
  - `systemPromptContainsTheInstructionsAndTheBookText` (captures the `Prompt`: the system message has the fixture's marker phrases, the user message is the question)
  - `thinkBlockIsStrippedFromTheAnswer`
- `ChatWithoutBookTests` (path set to a file that doesn't exist)
  - `appStartsAndChatReturns503WhenTheBookIsMissing`
- `OllamaChatOptionsTests`
  - `ollamaUsesA32kContextWindowWithThinkingOffAndA30mKeepAlive` (asserts the bound Ollama chat properties)
- `ChatWarmUpTests` (`app.chat.warm-up=true`, fixture book, mocked `ChatModel`, waits for the background call with Mockito `timeout()`)
  - `warmUpSendsTheSameSystemPromptAsChatOnceAtStartup`
  - `warmUpAsksForASingleToken`
  - `warmUpFailureDoesNotStopTheApp` (the model throws; the context is up and `/chat` still answers)
- `ChatWarmUpWithoutBookTests`
  - `noWarmUpCallWhenTheBookIsMissing`
- `OpenApiDocsTests`: add `chatEndpointIsDocumented` (`$.paths./chat.post` exists)

Manual: `backend/http/chat.http` against the running app with the real book and Ollama. I run it myself if Ollama and the book are available, and report what came back.

No E2E: there is no UI yet.

## Docs to update

- `README.md`: a new "The book" section (where to put the EPUB, `app.book.path`, never commit it, the 503 when it's missing), `/chat` under Running, a pointer to `backend/http/`, and the memory and first-request cost.
- `CLAUDE.md`: Project paragraph (`/chat` exists, the book location), Ollama paragraph (`num-ctx`, `think`), and `backend/http/` with `http-client.env.json` now exist.
- `docs/architecture.md`: status line, Backend row, the RAG-flow table's Read / Prompt and answer / Respond rows (what ALB-30 built: the full book in the system prompt, no retrieval yet).
- `docs/plans/ALB-30.md`: this plan, with a Status checklist.
- Added during implementation: README "Running" notes on the localhost binding, the 2,000-character question limit, the answer cap and the 503s; CLAUDE.md lines on the same, and on the test setup (`ChatModelStubs`, the test-only `config/application.properties`).
- Added after hand-off, at the developer's request: `docs/plans/ALB-30-test-guide.md` (manual test guide), shared IntelliJ run configurations in `.run/` for every project command, with a README section "From IntelliJ IDEA" and a CLAUDE.md convention to keep them in step.

## Out of scope

- Streaming (ALB-31), chunking, embeddings and retrieval (ALB-32), the pgvector store (ALB-10), uploads (ALB-11), citations (ALB-12), CORS and the frontend.
- Whether ALB-12 replaces `/chat` or adds `/ask` next to it. The `BookContext` interface allows either, so ALB-12 decides.

## Open risks

- Tika's EPUB output may keep CSS or navigation noise. I'll check the real book's extracted text locally (character count and a look for markup only, never pasted anywhere) and extend the cleanup if needed.
- Whether `qwen3:8b` on Ollama honours `think=false`. The strip step covers it either way.
- The warm-up processes about 21k tokens in the background, and that call may run past the HTTP client's read timeout. If it does, I raise the timeout (the property name gets checked for Boot 4.1), note it in README, and report it.
- Ollama's prompt cache lives in memory only. A restart of Ollama or of the model, a 30-minute idle gap, or a change to the prompt template means the next request processes the book again. Restarting the backend re-runs the warm-up.
- Not doing: caching answers per question. It only helps exact repeats and can serve stale answers.

## Status

Branch: `albercode10/alb-30-first-chat-endpoint-answer-questions`

- [x] 3. Plan approved (added after review: Ollama keep-alive and the startup warm-up, so the first question is fast)
- [x] 4. Red: failing tests (404, empty book, no warm-up call, Ollama properties unset)
- [x] 5. Green: implementation and docs (22 tests, `./mvnw verify` green)
- [x] 6. Manual-check artifacts (`backend/http/chat.http`) run against Ollama: book loaded (91,663 chars), warm-up 274 s, valid question 200 in 45 s (long answer, no `<think>`), off-book question 200 in 2.2 s ("the book does not cover…"), blank 400. The first run found a bug: `app.book.path` with `..` failed to bind to `Path`. Fixed with `BookPathBindingTests` (String property plus `file()`).
- [x] 7. E2E: none (no UI)
- [x] 8. Independent review (no blockers; findings at hand-off)
- [x] 9. Hand-off. Fixed review findings, test first (34 tests, `./mvnw verify` green):
  - Question capped at 2,000 characters (`@Size`), 400 above it.
  - Answers capped at `num-predict=1024`; a failed model call gives 503 "Model unavailable" (logged, no details to the client). No read timeout: it would cut off the ~4.5-minute warm-up.
  - `server.address=127.0.0.1` until there's auth.
  - Tests for an unreadable file and an EPUB with no text; the fixture lists chapters in a different zip/manifest order from the spine.
  - An unterminated `<think>` block is stripped to the end.
  - `ChatWarmUpTests` picks prompts by their question, not by call count, so the startup warm-up thread can't race it.
  - Re-review of the fixes and `.run/` (fresh correctness + security reviewers): no blockers. All five findings fixed, test first (45 tests, `./mvnw verify` green):
    - `spring.ai.retry.max-attempts=1`, backoff 1–2 s, so "Ollama down" answers 503 in seconds instead of ~22 minutes (`ModelRetryTests`).
    - A blank answer after stripping `<think>` is a 503; an answer cut at `num-predict` is returned and logged.
    - `config/LocalhostOnlyFilter`: 403 for any `Host` other than localhost, 127.0.0.1 or [::1] (DNS rebinding), with `LocalhostOnlyFilterTests`.
    - Comments on the question limit (worst case ~6k tokens) and on `server.address` corrected; README sentence about which configurations run in the terminal fixed.
    - Test guide updated: case 8 now expects a fast 503, new case 11 for the Host check.
  - Manual-check requests moved from IntelliJ `.http` files to a Bruno collection, at the developer's request (the IntelliJ HTTP Client needs a paid license; the developer clicks, Claude writes the requests). `backend/http/` (`chat.http`, `api-docs.http`, `http-client.env.json`) is replaced by `backend/bruno/` (Bruno 4, OpenCollection YAML): Local environment, Chat folder (valid, not covered, blank, missing, empty body, 2,001 and 2,000 characters, another host) and API docs folder (OpenAPI document, Swagger UI), each with status assertions and tests. README, CLAUDE.md and the test guide updated. ADR 0001 still mentions `backend/http/` in a consequence line; accepted ADRs aren't rewritten.
  - Deferred: escaping `</book>` in documents and an "ignore instructions in the book" line (needed once uploads or retrieval bring in other people's documents, ALB-11/ALB-32); a warning when the book doesn't fit the context.
