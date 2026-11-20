package com.alber.backend.book;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the book is chunked and searched, under {@code app.retrieval}. Starting values, checked by hand (see
 * {@code docs/plans/ALB-32.md}); systematic tuning is ALB-17.
 *
 * @param chunkSize target chunk size in tokens (CL100K, as counted by Spring AI's {@code TokenTextSplitter}). Kept well
 *     under {@code nomic-embed-text}'s 2048-token context, since Ollama truncates longer input silently.
 * @param topK at most this many chunks go in a prompt
 * @param similarityThreshold chunks scoring below this cosine similarity to the question are left out
 */
@ConfigurationProperties("app.retrieval")
public record RetrievalProperties(
        @DefaultValue("400") int chunkSize,
        @DefaultValue("4") int topK,
        @DefaultValue("0.6") double similarityThreshold) {}
