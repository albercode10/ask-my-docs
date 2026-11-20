package com.alber.backend.book;

import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The book's chunks, embedded once at startup into Spring AI's in-memory {@link SimpleVectorStore}, and searched for
 * each question.
 *
 * <p>Indexing runs in the constructor, so it's done before the app accepts requests. It's all or nothing: if any chunk
 * fails to embed (Ollama down, the embedding model not pulled), the index stays empty, a warning says why, and
 * {@link #isReady()} is false until a restart.
 *
 * <p>{@code nomic-embed-text} is trained with task prefixes, and Spring AI doesn't add them: chunks are embedded as
 * {@code search_document: …} and questions as {@code search_query: …}. Passages come back without the prefix.
 */
@Slf4j
@Component
public class BookIndex {

    static final String DOCUMENT_PREFIX = "search_document: ";

    static final String QUERY_PREFIX = "search_query: ";

    private static final String CHAPTER = "chapter";

    private static final String INDEX = "index";

    private final RetrievalProperties retrieval;

    private final @Nullable SimpleVectorStore store;

    @Autowired
    BookIndex(BookProperties book, RetrievalProperties retrieval, EmbeddingModel embeddingModel) {
        this(book.file(), retrieval, embeddingModel);
    }

    BookIndex(Path bookFile, RetrievalProperties retrieval, EmbeddingModel embeddingModel) {
        this.retrieval = retrieval;
        this.store = BookLoader.loadChapters(bookFile)
                .map(chapters -> index(chapters, retrieval, new KnownDimensions(embeddingModel)))
                .orElse(null);
    }

    /** Whether the book is indexed and can be searched. */
    public boolean isReady() {
        return store != null;
    }

    /**
     * The chunks closest to the question, best first: at most {@code app.retrieval.top-k}, none scoring below
     * {@code app.retrieval.similarity-threshold}. Empty when nothing in the book is close enough.
     *
     * @throws IllegalStateException when the book isn't indexed (check {@link #isReady()} first)
     * @throws RuntimeException when embedding the question fails
     */
    public List<BookPassage> search(String question) {
        if (store == null) {
            throw new IllegalStateException("The book isn't indexed.");
        }
        List<BookPassage> passages = store
                .similaritySearch(SearchRequest.builder()
                        .query(QUERY_PREFIX + question)
                        .topK(retrieval.topK())
                        .similarityThreshold(retrieval.similarityThreshold())
                        .build())
                .stream()
                .map(BookIndex::passage)
                .toList();
        if (log.isDebugEnabled()) {
            log.debug(
                    "Retrieved {} passages: {}",
                    passages.size(),
                    passages.stream()
                            .map(p -> "#%d (%.3f)".formatted(p.index(), p.score()))
                            .toList());
        }
        return passages;
    }

    private static @Nullable SimpleVectorStore index(
            List<BookChapter> chapters, RetrievalProperties retrieval, EmbeddingModel embeddingModel) {
        long start = System.nanoTime();
        List<BookChunk> chunks = BookChunker.chunk(chapters, retrieval.chunkSize());
        if (chunks.isEmpty()) {
            log.warn("The book has no chunks to index. POST /chat answers 503.");
            return null;
        }
        // A new store, kept only once every chunk is in: SimpleVectorStore adds one at a time and never rolls back.
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        try {
            store.add(chunks.stream().map(BookIndex::document).toList());
        } catch (RuntimeException e) {
            log.warn("Could not index the book: {} POST /chat answers 503 until a restart.", reason(e));
            return null;
        }
        log.info(
                "Indexed {} chunks from {} chapters in {} ms",
                chunks.size(),
                chapters.size(),
                Duration.ofNanos(System.nanoTime() - start).toMillis());
        return store;
    }

    private static Document document(BookChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(INDEX, chunk.index());
        if (chunk.chapter() != null) {
            metadata.put(CHAPTER, chunk.chapter());
        }
        return Document.builder()
                .id("chunk-" + chunk.index())
                .text(DOCUMENT_PREFIX + chunk.text())
                .metadata(metadata)
                .build();
    }

    private static BookPassage passage(Document document) {
        String text = document.getText() == null ? "" : document.getText();
        Object chapter = document.getMetadata().get(CHAPTER);
        Object index = document.getMetadata().get(INDEX);
        return new BookPassage(
                text.startsWith(DOCUMENT_PREFIX) ? text.substring(DOCUMENT_PREFIX.length()) : text,
                chapter == null ? null : chapter.toString(),
                index instanceof Number number ? number.intValue() : -1,
                document.getScore() == null ? 0 : document.getScore());
    }

    /** Why indexing failed, in words that say what to fix. */
    static String reason(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return "Ollama isn't reachable (" + e.getMessage() + "). Is it running?";
            }
        }
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (e instanceof NonTransientAiException && (message.startsWith("404") || message.contains("not found"))) {
            return "the embedding model isn't pulled in Ollama (" + e.getMessage()
                    + "). Run `ollama pull nomic-embed-text` and restart.";
        }
        return "embedding failed (" + e + ").";
    }

    /**
     * Remembers the model's vector size. {@code SimpleVectorStore} asks for it on every add and search, and the Ollama
     * model answers by embedding a test string each time: one extra request per question.
     */
    private static final class KnownDimensions implements EmbeddingModel {

        private final EmbeddingModel delegate;

        private volatile int dimensions;

        KnownDimensions(EmbeddingModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return delegate.call(request);
        }

        @Override
        public float[] embed(Document document) {
            return delegate.embed(document);
        }

        @Override
        public float[] embed(String text) {
            return delegate.embed(text);
        }

        @Override
        public int dimensions() {
            if (dimensions == 0) {
                dimensions = delegate.dimensions();
            }
            return dimensions;
        }
    }
}
