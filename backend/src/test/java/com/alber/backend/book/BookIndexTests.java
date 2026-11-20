package com.alber.backend.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.ResourceAccessException;

/**
 * At startup the book's chunks are embedded into an in-memory vector store, all or nothing; each question then gets
 * the closest chunks above the similarity threshold, with their chapter, index and score.
 */
@ExtendWith(OutputCaptureExtension.class)
class BookIndexTests {

    private static final RetrievalProperties DEFAULTS = new RetrievalProperties(400, 4, 0.6);

    @TempDir
    Path dir;

    private final KeywordEmbeddingModel embeddings = new KeywordEmbeddingModel();

    @Test
    void indexesEveryChunkAndLogsCountAndDuration(CapturedOutput output) throws Exception {
        BookIndex index = new BookIndex(TestEpubs.write(dir.resolve("book.epub")), DEFAULTS, embeddings);

        assertThat(index.isReady()).isTrue();
        assertThat(embeddings.embeddedTexts()).hasSize(2);
        assertThat(output).containsPattern("Indexed 2 chunks from 2 chapters in \\d+ ms");
    }

    @Test
    void chunksAreEmbeddedWithTheSearchDocumentPrefixAndQuestionsWithSearchQuery() throws Exception {
        BookIndex index = new BookIndex(TestEpubs.write(dir.resolve("book.epub")), DEFAULTS, embeddings);

        index.search("When were waxed moustaches popular?");

        assertThat(embeddings.embeddedTexts())
                .containsExactly(
                        embeddings.embeddedTexts().get(0),
                        embeddings.embeddedTexts().get(1),
                        "search_query: When were waxed moustaches popular?");
        assertThat(embeddings.embeddedTexts().subList(0, 2))
                .allSatisfy(text -> assertThat(text).startsWith("search_document: "));
    }

    @Test
    void searchReturnsTextChapterIndexAndScoreBestFirst() throws Exception {
        // Threshold 0 so both chunks come back, in order of similarity.
        BookIndex index = new BookIndex(
                TestEpubs.write(dir.resolve("book.epub")), new RetrievalProperties(400, 4, 0.0), embeddings);

        List<BookPassage> passages = index.search("How do I trim a beard with scissors?");

        assertThat(passages).hasSize(2);
        BookPassage best = passages.get(0);
        assertThat(best.text()).contains(TestEpubs.CHAPTER_TWO_MARKER).doesNotStartWith("search_document:");
        assertThat(best.chapter()).isEqualTo(TestEpubs.CHAPTER_TWO_TITLE);
        assertThat(best.index()).isEqualTo(1);
        assertThat(best.score()).isGreaterThan(0.5);
        BookPassage other = passages.get(1);
        assertThat(other.chapter()).isEqualTo(TestEpubs.CHAPTER_ONE_TITLE);
        assertThat(other.index()).isZero();
        assertThat(other.score()).isLessThan(best.score());
    }

    @Test
    void closestChunkIsReturnedAndUnrelatedChunkIsNot() throws Exception {
        BookIndex index = new BookIndex(TestEpubs.write(dir.resolve("book.epub")), DEFAULTS, embeddings);

        List<BookPassage> passages = index.search("When were waxed moustaches popular?");

        assertThat(passages)
                .extracting(BookPassage::text)
                .singleElement()
                .asString()
                .contains(TestEpubs.CHAPTER_ONE_MARKER);
    }

    @Test
    void vectorSizeIsAskedAtMostOnceNotOnEverySearch() throws Exception {
        BookIndex index = new BookIndex(TestEpubs.write(dir.resolve("book.epub")), DEFAULTS, embeddings);

        index.search("When were waxed moustaches popular?");
        index.search("How do I trim a beard?");

        // Ollama's model answers dimensions() by embedding a test string: one extra call per search otherwise.
        assertThat(embeddings.dimensionCalls()).isLessThanOrEqualTo(1);
    }

    @Test
    void searchReturnsAtMostTopK() throws Exception {
        BookIndex index = new BookIndex(
                TestEpubs.write(dir.resolve("book.epub")), new RetrievalProperties(400, 1, 0.0), embeddings);

        assertThat(index.search("Anything at all?")).hasSize(1);
    }

    @Test
    void nothingAboveTheThresholdGivesNoPassages() throws Exception {
        BookIndex index = new BookIndex(TestEpubs.write(dir.resolve("book.epub")), DEFAULTS, embeddings);

        assertThat(index.search("What is the capital of France?")).isEmpty();
    }

    @Test
    void failureOnAnyChunkLeavesTheIndexEmpty(CapturedOutput output) throws Exception {
        // Chapter one embeds fine; chapter two fails.
        embeddings.failWhen(text -> text.contains("scissors"), text -> new IllegalStateException("boom"));

        BookIndex index = new BookIndex(TestEpubs.write(dir.resolve("book.epub")), DEFAULTS, embeddings);

        assertThat(index.isReady()).isFalse();
        assertThatThrownBy(() -> index.search("When were waxed moustaches popular?"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(output).contains("Could not index the book").contains("boom");
    }

    @Test
    void ollamaDownIsLoggedAsUnreachable(CapturedOutput output) throws Exception {
        embeddings.failWhen(
                text -> true,
                text -> new ResourceAccessException(
                        "I/O error on POST request", new ConnectException("Connection refused")));

        BookIndex index = new BookIndex(TestEpubs.write(dir.resolve("book.epub")), DEFAULTS, embeddings);

        assertThat(index.isReady()).isFalse();
        assertThat(output).contains("Ollama isn't reachable").doesNotContain("ollama pull");
    }

    @Test
    void missingModelIsLoggedWithThePullCommand(CapturedOutput output) throws Exception {
        embeddings.failWhen(
                text -> true,
                text -> new NonTransientAiException("404 - {\"error\":\"model 'nomic-embed-text' not found\"}"));

        BookIndex index = new BookIndex(TestEpubs.write(dir.resolve("book.epub")), DEFAULTS, embeddings);

        assertThat(index.isReady()).isFalse();
        assertThat(output).contains("ollama pull nomic-embed-text").doesNotContain("isn't reachable");
    }

    @Test
    void noBookMeansNotReadyAndNoEmbeddingCalls() {
        BookIndex index = new BookIndex(dir.resolve("missing.epub"), DEFAULTS, embeddings);

        assertThat(index.isReady()).isFalse();
        assertThat(embeddings.embeddedTexts()).isEmpty();
    }
}
