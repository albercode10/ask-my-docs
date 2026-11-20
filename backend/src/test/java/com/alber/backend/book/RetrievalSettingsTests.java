package com.alber.backend.book;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Chunks are embedded with nomic-embed-text, which stays loaded as long as the chat model, and retrieval starts with
 * the values checked by hand in docs/plans/ALB-32.md.
 */
@SpringBootTest
class RetrievalSettingsTests {

    @Autowired
    private OllamaEmbeddingProperties embedding;

    @Autowired
    private RetrievalProperties retrieval;

    @Test
    void embeddingsUseNomicEmbedTextWithA30mKeepAlive() {
        assertThat(embedding.getModel()).isEqualTo("nomic-embed-text");
        assertThat(embedding.getKeepAlive()).isEqualTo("30m");
    }

    @Test
    void retrievalStartsWith400TokenChunksTop4AndThreshold0point6() {
        assertThat(retrieval.chunkSize()).isEqualTo(400);
        assertThat(retrieval.topK()).isEqualTo(4);
        assertThat(retrieval.similarityThreshold()).isEqualTo(0.6);
    }
}
