# ALB-32: Answer from the book's most relevant passages (in-memory RAG with nomic-embed-text)

## Context / Goal

`/chat` and `/chat/stream` send the whole book (~21k tokens) with every question: slow first answers, a 32k context, no path to longer documents. Index the book once at startup (chunks embedded with `nomic-embed-text` in Spring AI's `SimpleVectorStore`) and send only the closest chunks with each question. Ticket: https://linear.app/alberto-caro/issue/ALB-32/answer-from-the-books-most-relevant-passages-in-memory-rag-with-nomic

## Sources read

- ALB-32 description, acceptance criteria, agent notes (no comments, attachments or linked docs). Blockers ALB-30 (PR #12) and ALB-31 (PR #13) are merged on `origin/main` (`3dd4609`).
- CLAUDE.md, README (Prerequisites, The book), `docs/architecture.md`, `docs/plans/ALB-31.md`, the `book` and `chat` packages and their tests, Bruno `chat` folder.
- Spring AI 2.0.1 **sources** (from Maven Central): `SimpleVectorStore`, `EmbeddingModel`, `OllamaEmbeddingModel`, `OllamaEmbeddingAutoConfiguration`, `TokenTextSplitter`, `SearchRequest`, `Document`, `RetryUtils`.
- Local Ollama: `nomic-embed-text` is pulled (context length 2048, 768 dims); an unknown model answers `404 {"error":"model '…' not found"}`.
- The real EPUB's structure only (file names, counts; no text): 12 spine files, `toc.ncx` with 12 entries, one per spine file; every file's `<title>` is the same, so chapter titles must come from the NCX.

## Project profile

- `backend/`: Spring Boot 4.1.1 (spring-webmvc / spring-test 7.0.9), Spring AI 2.0.1 (`spring-ai-starter-model-ollama`, `spring-ai-tika-document-reader`), springdoc 3.2.0, Lombok, Java 21, Maven wrapper. Packages `com.alber.backend.book`, `.chat`.
- Tests: `@SpringBootTest` + `@AutoConfigureMockMvc` + `MockMvcTester`, `@MockitoBean ChatModel` + `ChatModelStubs.stubDefaultOptions`, fixture EPUB via `TestEpubs` + `@DynamicPropertySource`; test `config/application.properties` points `app.book.path` at a missing file and turns warm-up off.
- CI: `./mvnw -B -ntp verify` (tests + `spotless:check`), `scripts/test-docs.sh`.
- Manual checks: Bruno collection `backend/bruno/` (OpenCollection YAML).
- Branch: `albercode10/alb-32-answer-from-the-books-most-relevant` from up-to-date `main`. PR says `Fixes ALB-32`.

## Approach

**Retrieval in the app's own code, not `QuestionAnswerAdvisor`.** ALB-12 needs each passage's text, chapter, index and score; an advisor hides them inside the prompt. A plain `BookIndex.search(question)` returns them, and `/chat` builds the prompt from them. No `spring-ai-vector-store-advisor` dependency.

### `book` package

1. **`BookChapter(String title /* nullable */, String text)`** and **`BookLoader.loadChapters(Path)` → `Optional<List<BookChapter>>`** (replaces `load`). Reads the EPUB zip: `container.xml` → OPF → spine order → each spine file's text with Tika's lenient `JSoupParser` (as today) and the existing `clean`. Chapter title from the NCX (`navPoint` `content src`, fragment dropped → `navLabel`). A spine file not in the NCX continues the previous chapter's title (chapters split across files); files before the first entry have none. No NCX → all titles null. OPF/NCX are read with the JDK DOM parser hardened against XXE (no DTD loading, no external entities). Chapters with no text are dropped; same warnings and empty result as today for missing / unreadable / textless books.
2. **`BookPassage(String text, String chapter /* nullable */, int index, double score)`**: what retrieval returns (and ALB-12 will use as sources).
3. **`RetrievalProperties`** `@ConfigurationProperties("app.retrieval")`: `chunkSize` (tokens, default **400**), `topK` (default **4**), `similarityThreshold` (default **0.6** after measuring; planned 0.5, see Starting values).
4. **`BookChunker`**: per chapter, `TokenTextSplitter.builder().withChunkSize(chunkSize)` (chunks never cross chapters); global `index` from 0 in book order, deterministic. 400 cl100k tokens stays well under nomic's 2048-token context, so Ollama never truncates silently.
5. **`BookIndex`** (`@Component`, replaces `FullBookContext` and the `BookContext` interface): in its constructor (so before Tomcat accepts requests) loads chapters, chunks them and embeds every chunk into a **new** `SimpleVectorStore`; the store is kept only if every chunk embedded (all or nothing, since `SimpleVectorStore.doAdd` stores one at a time). Logs `Indexed N chunks from M chapters in X ms`. On failure logs one warning naming the cause:
   - `ConnectException` in the cause chain → "Ollama isn't reachable at …";
   - `NonTransientAiException` with 404 / "not found" → "the embedding model nomic-embed-text isn't pulled: run `ollama pull nomic-embed-text`";
   - anything else → generic message with `e.toString()`.
   API: `boolean isReady()`; `List<BookPassage> search(String question)` (top-k above threshold, best first).
   **nomic task prefixes:** Spring AI doesn't add them (`OllamaEmbeddingModel.embed(Document)` embeds `getText()` as is). Chunks are stored as `search_document: <chunk>`, the query is `search_query: <question>`; `BookPassage.text` has the prefix stripped. Metadata: `chapter` (omitted when null; `Document` rejects null values), `index`.
6. **Dependency**: `org.springframework.ai:spring-ai-vector-store` (BOM-managed) for `SimpleVectorStore`.

### `chat` package

7. **`ChatService`**: `answer` and `stream` first check `bookIndex.isReady()` eagerly → `BookUnavailableException` (503 before any stream, unchanged). Then `search(question)`:
   - `/chat`: a failed search → `ModelUnavailableException` → the same 503 "Model unavailable" body as a failed model call.
   - `/chat/stream`: the search runs **inside** the `Flux` (deferred), so a failure becomes the `error` event, as for a model failure (AC 5). The timeout covers it too.
8. **`BookPrompt.system(List<BookPassage>)`**: fixed instructions first (now "passages from the book that may be relevant"), then each passage as `<passage>` with its chapter line, or a fixed line saying no passage matched. Built by concatenation (braces safe, as today).
9. **`ChatWarmUp` / `warmUp()`**: there is no fixed book prompt to cache any more, so it loads `qwen3:8b` into memory with the instructions-only prompt (no search, no embedding), 1 token. Still skipped when the index isn't ready. Log text updated.
10. **503 body when indexing failed**: same `BookUnavailableException` → status 503, title stays "Book not loaded", detail broadened to "The book isn't loaded or indexed, so there's nothing to answer from. See the backend log and README → The book." (keeps `/chat` and `/chat/stream` identical).
11. `ChatController` OpenAPI descriptions: "the most relevant passages of the book" instead of "the whole book"; 503 mentions indexing. Request/response formats unchanged.

### Config (`application.properties`)

- `spring.ai.ollama.embedding.model=nomic-embed-text`, `spring.ai.ollama.embedding.keep-alive=30m`.
- `spring.ai.ollama.chat.num-ctx=8192` (was 32768): 4 × 400-token passages + instructions + a 2,000-char question + 1,024-token answer ≈ 4k; saves ~4 GB of KV cache. Comments updated.
- `app.retrieval.chunk-size=400`, `app.retrieval.top-k=4`, `app.retrieval.similarity-threshold=0.6` with comments.
- `app.chat.stream-timeout` stays 5m; its comment no longer talks about processing the whole book.

### Tests: how indexing stays offline and deterministic

- Default test config has no book, so nothing is indexed and the auto-configured `OllamaEmbeddingModel` is never called (AC 6).
- Tests with the fixture book replace the embedding model with **`@TestBean EmbeddingModel`** (spring-test 7.0.9, a bean override like `@MockitoBean`) returning a test **`KeywordEmbeddingModel`**: a fixed vocabulary (e.g. `wax`, `moustache`, `cyclist`, `beard`, `trim`, `scissors`) + a small constant component (no zero vectors), so "waxed moustaches" lands on chapter one and not chapter two, and an unrelated question scores below 0.5. It records the texts it embedded (prefix checks) and can be told to fail (question-time failure). A `@MockitoBean` can't be stubbed before the context starts, and startup indexing needs real vectors; CLAUDE.md's testing line gets this added.
- `BookIndex` is a plain class too: all-or-nothing and failure logging are unit-tested without Spring (`OutputCaptureExtension` for logs).
- `TestEpubs` gets a `toc.ncx` (EPUB 2), with NCX labels distinct from the `<h1>` text so the test proves where titles come from, plus a variant without NCX. The chapter texts stay made up.

No ADR: in-memory first then pgvector is ADR 0002; app code vs advisor for retrieval is local to `chat`/`book`.

## APIs verified (Spring AI 2.0.1 sources, spring-test 7.0.9 jar)

- `SimpleVectorStore.builder(EmbeddingModel).build()`, `add(List<Document>)` (embeds one by one via `embed(Document)`, no rollback), `similaritySearch(SearchRequest)` (cosine, `>= threshold`, sorted, `limit(topK)`, sets `Document.getScore()`); artifact `spring-ai-vector-store`.
- `SearchRequest.builder().query().topK().similarityThreshold().build()`.
- `TokenTextSplitter.builder().withChunkSize(int).build()`, `split(Document)` copies metadata (CL100K tokens).
- `OllamaEmbeddingModel.embed(Document)` embeds `document.getText()` only; no retry around embedding calls; auto-config `@ConditionalOnMissingBean`, property `spring.ai.ollama.embedding.model`.
- Ollama HTTP errors → `RetryUtils` default handler: 4xx → `NonTransientAiException("<status> - <body>")`; connection refused → Spring `ResourceAccessException` with `ConnectException` cause.
- `org.springframework.test.context.bean.override.convention.TestBean` present in spring-test 7.0.9.

## Acceptance criteria

1. Startup with book + Ollama: book split into chunks, each embedded with `nomic-embed-text` into `SimpleVectorStore`; log shows chunk count and time; indexing done before requests are accepted; any chunk failure → empty store, behaves as AC 5.
2. Each chunk has its index (0-based, stable across starts) and chapter title (or none). Fixture with 2 chapters: every chunk has the right title. Search returns text, chapter, index, score.
3. `/chat` and `/chat/stream` prompts contain the top chunks (≤ `app.retrieval.top-k`, default 4), not the whole book; the clearly closest chunk is in, an unrelated one out.
4. No chunk above `app.retrieval.similarity-threshold` → request succeeds, prompt has no chunks.
5. Startup: Ollama down or model not pulled → app starts, warning says which; `/chat` and `/chat/stream` 503. Question time: embedding fails → `/chat` 503 "Model unavailable", `/chat/stream` `error` event.
6. `./mvnw test` passes without Ollama or the real book; no test indexes `data/books/`.
7. Plan records chunk size, top-k and threshold; manual run: "how to wax a moustache" retrieves ≥ 1 chunk, "capital of France" retrieves none.
8. `/chat` and `/chat/stream` formats unchanged; README lists `ollama pull nomic-embed-text` and says the book is indexed at startup; CLAUDE.md mentions the embedding model and the in-memory store.

## Test cases

Unit:
- `BookLoaderTests`: `readsEachChapterInSpineOrderWithItsTocTitle`, `spineFileMissingFromTheTocContinuesThePreviousChapter`, `epubWithoutTocGivesChaptersWithoutTitles`, existing cleaning / not-an-EPUB / no-text / missing-file cases on `loadChapters`, `tocWithExternalEntityIsNotResolved` (XXE).
- `BookChunkerTests`: `chunksNeverCrossChapters`, `indexesCountFromZeroInBookOrderAndAreStable`, `longChapterIsSplitIntoChunksNoLongerThanTheChunkSize`, `everyChunkCarriesItsChapterTitleOrNone`.
- `BookIndexTests` (fixture + `KeywordEmbeddingModel`): `indexesEveryChunkAndLogsCountAndDuration`, `chunksAreEmbeddedWithTheSearchDocumentPrefixAndQuestionsWithSearchQuery`, `searchReturnsTextChapterIndexAndScoreBestFirst`, `closestChunkIsReturnedAndUnrelatedChunkIsNot`, `searchReturnsAtMostTopK`, `nothingAboveTheThresholdGivesNoPassages`, `failureOnAnyChunkLeavesTheIndexEmpty`, `ollamaDownIsLoggedAsUnreachable`, `missingModelIsLoggedWithThePullCommand`, `noBookMeansNotReadyAndNoEmbeddingCalls`.
- `BookPromptTests`: `instructionsComeFirstThenEachPassageWithItsChapter`, `noPassagesGivesTheNoMatchLine`, `bracesInPassagesReachThePromptUnchanged`.

Spring (`@TestBean` keyword embedder, `@MockitoBean ChatModel`):
- `ChatEndpointTests`: `systemPromptContainsTheClosestPassageAndNotTheUnrelatedOne` (replaces the whole-book test), `questionTheBookDoesNotCoverIsAnsweredWithNoPassages`, `embeddingFailureAtQuestionTimeReturns503ModelUnavailable`; existing 400/503/think cases stay.
- `ChatStreamEndpointTests`: same prompt test for the stream, `embeddingFailureAtQuestionTimeGivesAnErrorEvent`; existing cases stay.
- `ChatWithIndexingFailureTests`: embedder failing at startup → context starts, `/chat` and `/chat/stream` 503 with the same body.
- `ChatWithoutBookTests`: add `noBookMeansTheEmbeddingModelIsNeverCalled`.
- `ChatWarmUpTests`: `warmUpSendsTheInstructionsWithoutPassagesOrSearch` (replaces "same system prompt as chat"), single-token / same-context-size and failure cases kept.
- `OllamaChatOptionsTests`: num-ctx 8192; `OllamaEmbeddingOptionsTests`: model `nomic-embed-text`, keep-alive 30m; retrieval defaults 400 / 4 / 0.5.
- `OpenApiDocsTests` unchanged (contracts same).

Manual (Bruno, real Ollama + book): **Valid question** (wax a moustache) answers from the book; **Question the book doesn't cover** (capital of France) says the book doesn't cover it; startup log shows chunk count and time. For AC 7 I'll also log, at DEBUG, each search's indexes and scores (`logging.level.com.alber.backend.book=debug` while checking), and record the measured scores and final threshold here.

## Docs to update

- `README.md`: status line; Architecture table (Ollama row: embeddings, 8k context); Prerequisites add `ollama pull nomic-embed-text`; "The book": indexed at startup, log line, retrieval settings, what happens when Ollama/the embed model is missing; replace the whole-book / 32k / 4.5-minute paragraph.
- `CLAUDE.md`: Project paragraph (retrieval, `BookIndex`, `app.retrieval.*`, num-ctx 8192, embedding model, `SimpleVectorStore`, `ollama pull nomic-embed-text`); Workflow testing line (`@TestBean` keyword embedding model for tests with a book).
- `docs/architecture.md`: status, Backend/Ollama rows, Chunk / Embed and store / Retrieve / Prompt rows "Built (ALB-32)", Read row (chapters from the NCX).
- IntelliJ: new `.run/Setup_pull_nomic_embed_text_once.run.xml` (`ollama pull nomic-embed-text`, like the qwen3 one) and the README "From IntelliJ IDEA" Setup row.
- Bruno `chat/Valid question.yml` and `Question the book doesn't cover.yml` docs: retrieval note.

## Out of scope

Persisting the store, pgvector (ALB-10), sources and exact "I don't know" (ALB-12), uploads (ALB-11), systematic tuning (ALB-17).

## Open risks

- Threshold 0.5 is a guess for nomic with prefixes; step 6 measures and adjusts it (AC 7). If no single threshold separates the two manual questions, I'll stop and report.
- cl100k token counts differ from nomic's WordPiece; 400 leaves ample margin under 2048.
- Smaller prompts mean Ollama's prompt cache only helps with the instructions prefix; each answer still processes ~2k tokens, far less than 21k.

## Starting values (AC 7, measured 2026-11-19)

Real book, `nomic-embed-text`, with task prefixes. Startup: **56 chunks from 10 chapters, indexed in ~4.5 s** (12 spine files; 2 have no text).

| Setting | Value |
| --- | --- |
| `app.retrieval.chunk-size` | 400 tokens (CL100K) |
| `app.retrieval.top-k` | 4 |
| `app.retrieval.similarity-threshold` | **0.6** (planned 0.5; raised after measuring) |
| `spring.ai.ollama.chat.num-ctx` | 8192 |

Top similarity per question (chunk index, score):

| Question | Top 4 | With 0.6 |
| --- | --- | --- |
| How do I wax a moustache? | #46 0.844, #42 0.812, #47 0.774, #43 0.758 | 4 chunks ✅ |
| How often should I trim my beard? | #45 0.756 … #41 0.715 | 4 |
| How do I shave my head? | #43 0.760 … #45 0.710 | 4 |
| What is a goatee? | #18 0.678, #21 0.641, #19 0.636, #20 0.620 | 4 |
| Should I use beard oil? | #45 0.771 … #42 0.696 | 4 |
| Which comb is best for a moustache? | #41 0.780 … #40 0.743 | 4 |
| What is the capital of France? | #7 0.545, #36 0.533, #35 0.527, #8 0.525 | none ✅ |
| Who won the football World Cup in 2018? | #24 0.545 … #38 0.513 | none |
| How do I bake sourdough bread? | #43 0.520 … | none |
| What is the best programming language? | none ≥ 0.5 | none |

Off-topic questions top out at 0.545, on-topic ones start at 0.678, so 0.6 sits in the gap. With 0.5, "capital of France" got 4 unrelated chunks.

## Changes during implementation

- `BookIndex` wraps the embedding model in `KnownDimensions`: `SimpleVectorStore` calls `dimensions()` on every add and search, and `OllamaEmbeddingModel` answers it by embedding a test string, so every question cost two embedding calls. Test: `BookIndexTests.vectorSizeIsAskedAtMostOnceNotOnEverySearch`.
- `logging.level.org.springframework.ai.vectorstore.SimpleVectorStore=warn`: it logs one INFO line per embedded chunk.
- The settings test is `book/RetrievalSettingsTests` (embedding model, keep-alive, retrieval defaults), not `OllamaEmbeddingOptionsTests`.
- "No passages" assertions check the text after the instructions, since the instructions themselves name the `<passage>` tag.
- Extra test: `ChatEndpointTests.passageWithCurlyBracesReachesTheModelUnchanged` (kept from the whole-book test).

## Status

Branch: `albercode10/alb-32-answer-from-the-books-most-relevant` (from `main` at `3dd4609`).

- [x] Plan approved
- [x] Step 4: failing tests (red)
- [x] Step 5: implementation and docs (green)
- [x] Step 6: Bruno requests and manual checks (threshold measured)
- [x] Step 7: E2E (none planned)
- [x] Step 8: fresh-eyes review
- [ ] Step 9: hand-off
