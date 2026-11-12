package com.alber.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A failed model call answers 503 within seconds. Spring AI's default retry (10 attempts, backoff up to 3 minutes)
 * would hold the request for about 22 minutes when Ollama is down. The tests mock the model, so this property is the
 * only thing that pins it.
 */
@SpringBootTest
class ModelRetryTests {

    @Autowired
    private SpringAiRetryProperties retry;

    @Test
    void modelCallsAreNotRetriedForMinutes() {
        assertThat(retry.getMaxAttempts()).isEqualTo(1);
        assertThat(retry.getBackoff().getInitialInterval()).isLessThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(retry.getBackoff().getMaxInterval()).isLessThanOrEqualTo(Duration.ofSeconds(2));
    }
}
