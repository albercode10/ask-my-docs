package com.alber.backend.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A question about the book. */
record ChatRequest(
        @Schema(
                description = "The question to ask, at most " + MAX_QUESTION_LENGTH + " characters",
                example = "How should I trim a moustache?",
                maxLength = MAX_QUESTION_LENGTH)
        @NotBlank
        @Size(max = MAX_QUESTION_LENGTH)
        String question) {

    /**
     * About 500 tokens in English or Spanish; at worst (scripts that take several tokens per character) about 6k.
     * The book takes ~21k of the 32k context and an answer up to ~1k, so even the worst case (~28k) fits. A longer
     * question could push the instructions or the book out of the window, and Ollama would cut it silently.
     */
    static final int MAX_QUESTION_LENGTH = 2000;
}
