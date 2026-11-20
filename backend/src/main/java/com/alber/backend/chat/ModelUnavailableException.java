package com.alber.backend.chat;

/**
 * Embedding the question or the model call failed (Ollama down, model not pulled, …), or the model returned no
 * answer. Mapped to 503 by {@link ChatExceptionHandler}.
 */
class ModelUnavailableException extends RuntimeException {

    ModelUnavailableException(String message) {
        super(message);
    }

    ModelUnavailableException(Throwable cause) {
        super("The model call failed.", cause);
    }
}
