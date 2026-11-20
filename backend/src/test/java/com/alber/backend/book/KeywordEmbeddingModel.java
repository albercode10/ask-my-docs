package com.alber.backend.book;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A deterministic, offline stand-in for {@code nomic-embed-text}: one dimension per keyword (1 when the text contains
 * it), plus a small constant so no vector is zero. "Waxed moustaches" lands on {@link TestEpubs}' chapter one, "beard
 * trimming" on chapter two, and a question with none of the keywords scores about 0.06 against any chunk, far below the
 * default threshold of 0.6.
 *
 * <p>Tests with a book replace the real embedding model with it before the context starts, since startup indexing
 * needs real vectors and a {@code @MockitoBean} can't be stubbed that early:
 *
 * <pre>{@code
 * @TestBean(methodName = KeywordEmbeddingModel.FACTORY)
 * private EmbeddingModel embeddingModel;
 * }</pre>
 *
 * It records every text it embedded, and fails on the texts {@link #failWhen} matches.
 */
public class KeywordEmbeddingModel implements EmbeddingModel {

    /** Factory method for {@code @TestBean(methodName = ...)}. */
    public static final String FACTORY = "com.alber.backend.book.KeywordEmbeddingModel#create";

    private static final List<String> KEYWORDS = List.of("wax", "moustache", "cyclist", "beard", "trim", "scissors");

    private static final float BASE = 0.1f;

    private final List<String> embedded = new CopyOnWriteArrayList<>();

    private final AtomicInteger dimensionCalls = new AtomicInteger();

    private volatile Predicate<String> failWhen = text -> false;

    private volatile Function<String, RuntimeException> failure = text -> new IllegalStateException("Embedding failed");

    public static EmbeddingModel create() {
        return new KeywordEmbeddingModel();
    }

    /** Makes every later call whose text matches throw {@code failure}. */
    public KeywordEmbeddingModel failWhen(Predicate<String> texts, Function<String, RuntimeException> failure) {
        this.failWhen = texts;
        this.failure = failure;
        return this;
    }

    /** Makes every later question (a {@code search_query:} text) fail, as when Ollama goes down after startup. */
    public KeywordEmbeddingModel failQuestions() {
        return failWhen(
                text -> text.startsWith("search_query:"), text -> new IllegalStateException("Connection refused"));
    }

    /** Back to embedding everything, with no record of earlier calls. */
    public void reset() {
        failWhen = text -> false;
        embedded.clear();
    }

    /** Every text embedded so far, in order. */
    public List<String> embeddedTexts() {
        return List.copyOf(embedded);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> results = new ArrayList<>();
        for (String text : request.getInstructions()) {
            results.add(new Embedding(vector(text), results.size()));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    /** How often {@link #dimensions} was asked. The real Ollama model embeds a test string to answer it. */
    public int dimensionCalls() {
        return dimensionCalls.get();
    }

    @Override
    public int dimensions() {
        dimensionCalls.incrementAndGet();
        return KEYWORDS.size() + 1;
    }

    private float[] vector(@Nullable String text) {
        String value = text == null ? "" : text;
        if (failWhen.test(value)) {
            throw failure.apply(value);
        }
        embedded.add(value);
        String lower = value.toLowerCase(Locale.ROOT);
        float[] vector = new float[KEYWORDS.size() + 1];
        for (int i = 0; i < KEYWORDS.size(); i++) {
            vector[i] = lower.contains(KEYWORDS.get(i)) ? 1f : 0f;
        }
        vector[KEYWORDS.size()] = BASE;
        return vector;
    }
}
