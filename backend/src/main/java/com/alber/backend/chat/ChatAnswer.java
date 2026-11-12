package com.alber.backend.chat;

import io.swagger.v3.oas.annotations.media.Schema;

/** The model's answer. Named so it doesn't clash with Spring AI's {@code ChatResponse}. */
record ChatAnswer(
        @Schema(description = "The answer, grounded in the book")
        String answer) {}
