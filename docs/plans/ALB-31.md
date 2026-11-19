# ALB-31: Stream chat answers token by token as server-sent events

## Context / Goal

A `POST /chat` answer arrives only when it's complete, after seconds of nothing. Add `POST /chat/stream` (same JSON body) that streams the answer as server-sent events: `token` `{"text"}` per piece, then `done` `{}`, or `error` `{"message"}` on failure. ALB-15's chat page needs it. Ticket: https://linear.app/alberto-caro/issue/ALB-31/stream-chat-answers-token-by-token-as-server-sent-events

## Sources read

- ALB-31 description, acceptance criteria and agent notes (no comments, attachments or linked docs). Blocked by ALB-30: merged (PR #12, `bf70d54` on `origin/main`).
- `docs/plans/ALB-30.md`, CLAUDE.md, README `/chat` sections, `docs/architecture.md`, the `chat` package and its tests, the Bruno `chat` folder.
- Spring AI 2.0.1 docs (Context7): `ChatClient…stream()` → `content()` / `chatResponse()`, `Flux` returned from an MVC controller.
- Bruno docs (Context7): SSE requests need `Accept: text/event-stream`; Bruno streams the body live, Send becomes Cancel; collection runner and CLI skip SSE requests.
- `spring-webmvc-7.0.9.jar` (javap): `ReactiveTypeHandler.STREAMING_TIMEOUT_VALUE = -1`, so a streamed `Flux` has no MVC async timeout. The time limit must come from our own `Flux.timeout`.

## Project profile

- `backend/`: Spring Boot 4.1.1 (spring-webmvc 7.0.9), Spring AI 2.0.1 (`spring-ai-starter-model-ollama`, brings Reactor), springdoc 3.2.0, Lombok, Java 21, Maven wrapper. Package `com.alber.backend.chat`.
- Tests: `@SpringBootTest` + `@AutoConfigureMockMvc` + `MockMvcTester` (AssertJ), `@MockitoBean ChatModel` with `ChatModelStubs.stubDefaultOptions`, fixture book via `TestEpubs.writeToTempDir()` + `@DynamicPropertySource`. One class per behaviour, Javadoc line on top.
- CI: `./mvnw -B -ntp verify` (tests + `spotless:check`), `scripts/test-docs.sh`.
- Manual checks: Bruno collection `backend/bruno/` (OpenCollection YAML). The ticket names `backend/http/chat.http`, but the repo moved to Bruno; the requests go there instead.
- Branch: `albercode10/alb-31-stream-chat-answers-token-by-token-as` from up-to-date `main`. PR says `Fixes ALB-31`.

## Approach

One path up to the model call: `/chat` and `/chat/stream` both go through `ChatService.prompt(question)`, which resolves the book (or throws `BookUnavailableException`) before anything is returned. `@Valid` and that check run before the controller returns a `Flux`, so 400/503 come from the same handlers as `/chat` and no stream is opened (AC 4).

1. **`chat/ChatService.stream(String question)`** → `Flux<String>`. Calls `prompt(question)` eagerly, then `.stream().chatResponse()`, maps each response to its output text (nulls and empty pieces dropped), passes it through a per-subscription `ThinkingFilter` (`Flux.defer`), and applies `.timeout(streamTimeout)`. Uses `chatResponse()` rather than `content()` so ALB-12 can read metadata later. No `strip()`: text goes out byte for byte (AC 2).
2. **`chat/ThinkingFilter`** (package-private, stateful, one per stream): `String accept(String piece)` returns the visible part; `String finish()` flushes what it held back. Drops everything between `<think>` and `</think>` even when a tag is split across pieces (holds back a trailing partial `<`, `<th`, …; an unterminated block is dropped to the end, like `stripThinking`). Defense in depth on top of `think=false` (AC 3). Side effect: a piece that ends in a possible tag start is merged into the next token; joined text is unchanged.
3. **`chat/ChatProperties`**: `@ConfigurationProperties("app.chat")` record with `Duration streamTimeout` (default `5m`). `application.properties`: `app.chat.stream-timeout=5m` with a comment (cold first request over the whole book takes ~4.5 min).
4. **`chat/ChatController`**: `POST /chat/stream`, `consumes` JSON, `produces` `text/event-stream`, returns `Flux<ServerSentEvent<?>>`: `token` events with record `TokenEvent(String text)`, then `done` with `Map.of()` (→ `{}`). `onErrorResume`: logs (WARN with `e.toString()` for model errors, WARN "timed out after …" for `TimeoutException`) and emits one `error` event with record `StreamError(String message)`, then completes; no `done`. Fixed messages: model failure "The model stopped before finishing the answer. Check that Ollama is running with the configured model pulled."; timeout "The answer took longer than the time limit." No exception text. `doOnCancel` logs at DEBUG (a disconnect isn't an error). Annotated with `@Operation` / `@ApiResponse` 200 (text/event-stream, describing the three events), 400, 503.
5. Client disconnect: MVC's `ReactiveTypeHandler` cancels the subscription when the connection fails; cancellation propagates through `timeout` / `map` to the model `Flux`, which closes the Ollama call (AC 7).
6. **Bruno** (`backend/bruno/chat/`): `Streamed answer.yml` (`Accept: text/event-stream`, `timeout: 0`, assertion status 200, test that the body has `event:token` and `event:done` and no `<think>`; docs note: first request after startup is slow, Cancel checks disconnect) and `Streamed answer, blank question.yml` (400, problem+json). Folder seqs adjusted.
7. **Docs**: README (status line; `/chat` section gets `/chat/stream`, its three events, `curl -N` example, time limit), CLAUDE.md (project paragraph: `/chat/stream`, events, `app.chat.stream-timeout`), `docs/architecture.md` (status, Built-in tables: streaming built in ALB-31).

No ADR: SSE over MVC with a Reactor `Flux` is local to this endpoint and was already listed in the architecture's Respond step.

## APIs verified

- `ChatClient.ChatClientRequestSpec.stream().chatResponse()` → `Flux<ChatResponse>` (Spring AI 2.0.1 docs).
- Returning a `Flux` from a Spring MVC controller is streamed (Spring AI docs; `ReactiveTypeHandler` in spring-webmvc 7.0.9 with no timeout).
- `ServerSentEvent` (spring-web) as the element type, serialized with message converters: check while writing tests.
- Bruno 4 SSE: `Accept: text/event-stream` header.

## Acceptance criteria

1. Streamed answer: 200 `text/event-stream`, one `token` per non-empty piece in order, one `done`, stream closes.
2. Exact text: joined `token` texts equal the pieces joined byte for byte (spaces, line breaks, `é`, `“ ”`); empty pieces skipped; no pieces → `done` only.
3. No `<think>…</think>` text in any `token`, even with tags split across pieces.
4. Blank, missing, malformed or overlong question → 400; no book → 503; status and body the same as `/chat`; model never called.
5. Model failure (before or after some tokens) → one `error` event with a fixed generic message, no `done`, stream closes.
6. Time limit `app.chat.stream-timeout` (default 5m) → `error` event and close.
7. Client disconnect cancels the model stream (cancel signal in tests; Ollama stopping checked by hand).
8. `/chat` unchanged (existing tests stay green). Bruno has the streaming request with the slow-first-request note; README and CLAUDE.md describe `/chat/stream` and its events.

## Test cases

Helper `chat/SseEvents.parse(String)` → list of `(event, data)`; bodies read as UTF-8.

`ChatStreamEndpointTests` (`@SpringBootTest`, `MockMvcTester`, `@MockitoBean ChatModel`, fixture book):
- `streamsOneTokenEventPerPieceThenDone`
- `joinedTokenTextEqualsTheModelsPiecesExactly` (leading spaces, `\n`, `é`, `“ ”`)
- `emptyPiecesAreSkipped`
- `answerWithNoPiecesGivesDoneOnly`
- `thinkBlockSplitAcrossPiecesNeverReachesAToken`
- `invalidQuestionsGive400WithTheSameBodyAsChat` (blank, missing field, empty body, malformed JSON, 2,001 chars; model `stream` never called)
- `modelFailureBeforeAnyTokenGivesAnErrorEventAndNoDone`
- `modelFailureAfterSomeTokensGivesThoseTokensThenAnErrorEvent` (message has no exception text)
- `systemPromptContainsTheInstructionsAndTheBookText` (same prompt as `/chat`)
- `clientDisconnectCancelsTheModelStream` (controller `Flux` subscribed and cancelled after the first event; stub's `doOnCancel` fired)

`ChatStreamTimeoutTests` (`app.chat.stream-timeout=200ms`): `answerThatDoesNotFinishInTimeGivesAnErrorEventAndCancelsTheModel` (`Flux.never()`).

`ChatWithoutBookTests`: `streamReturns503WithTheSameBodyAsChatWhenTheBookIsMissing`.

`ThinkingFilterTests` (unit): text without tags passes through; closed block dropped; tag split across pieces dropped; unterminated block dropped to the end; held `<` that isn't a tag is released by the next piece or `finish()`.

`OpenApiDocsTests`: `/chat/stream` is documented.

Manual (Bruno, real Ollama): **Streamed answer** (tokens arrive live, `done` last, no `<think>`), **Cancel** mid-answer (backend logs no error; Ollama stops, `ollama ps` / its log), **blank question** 400.

## Docs to update

- `README.md`: status line, `/chat` section (stream endpoint, events, curl `-N`, time limit).
- `CLAUDE.md`: project paragraph on `/chat/stream`, events and `app.chat.stream-timeout`.
- `docs/architecture.md`: status line and Built-in tables.

## Out of scope

Chat page (ALB-15), dev proxy/CORS (ALB-7), sources in the stream (ALB-12), `Last-Event-ID`/retries/heartbeats, concurrency limits, history, auth.

## Open risks

- 400/503 with `Accept: text/event-stream`: Spring should still render the `ProblemDetail` as `application/problem+json`; the AC 4 tests prove it, else the exception handler sets the content type explicitly.
- `Content-Type` may come without `;charset=UTF-8`; SSE is UTF-8 by spec. Tests decode as UTF-8 and check the header is compatible with `text/event-stream`.
- Whether Bruno runs `tests` scripts on an SSE response isn't documented; the status assertion is the minimum, scripts may not show.
- Whether Ollama stops generating when the HTTP call is cancelled is only checked by hand.

## Review fixes

1. Time limit is now for the whole answer (a deadline that cancels the model stream), not the gap between pieces: `answerThatKeepsTricklingPastTheLimitStillEndsWithAnErrorEvent`.
2. Disconnect before the first token is only noticed on the next write: documented in README and the Bruno request.
3. An answer with no visible text gives `done` only (unlike `/chat`'s 503): documented in README, OpenAPI and `ChatService.stream`.
4. Whitespace right after a `</think>` block is dropped, like `/chat`: `whitespaceRightAfterABlockIsDroppedLikeChatDoes`.
5. `ChatStreamEvents.Error` renamed `ErrorData`.
6. A streamed answer cut off by `num-predict` is logged at INFO.

## Status

Branch: `albercode10/alb-31-stream-chat-answers-token-by-token-as`

- [x] Plan approved, branch created
- [x] 4. Red: failing tests
- [x] 5. Green: implementation and docs
- [x] 6. Manual-check artifacts (Bruno)
- [x] 7. E2E: none planned
- [x] 8. Independent review
- [x] 9. Hand-off
- [x] Review fixes (all six findings)
