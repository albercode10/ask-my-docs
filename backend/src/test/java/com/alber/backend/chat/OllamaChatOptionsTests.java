package com.alber.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatProperties;
import org.springframework.ai.model.ollama.autoconfigure.OllamaThinkProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The whole book fits in the context window, the answer has no reasoning trace, and the model (with Ollama's prompt
 * cache) stays loaded between questions.
 */
@SpringBootTest
class OllamaChatOptionsTests {

    @Autowired
    private OllamaChatProperties ollama;

    @Test
    void ollamaUsesA32kContextWindowWithThinkingOffAndA30mKeepAlive() {
        assertThat(ollama.getModel()).isEqualTo("qwen3:8b");
        assertThat(ollama.getNumCtx()).isEqualTo(32768);
        assertThat(ollama.getThink()).isEqualTo(OllamaThinkProperties.FALSE);
        assertThat(ollama.getKeepAlive()).isEqualTo("30m");
    }

    @Test
    void answersAreCappedAtAbout1000Tokens() {
        assertThat(ollama.getNumPredict()).isEqualTo(1024);
    }
}
