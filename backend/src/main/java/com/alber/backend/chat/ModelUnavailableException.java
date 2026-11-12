package com.alber.backend.chat;

/**
 * The model call failed (Ollama down, model not pulled, …) or returned no answer. Mapped to 503 by
 * {@link ChatExceptionHandler}.
 */
class ModelUnavailableException extends RuntimeException {

    ModelUnavailableException(String message) {
        super(message);
    }

    ModelUnavailableException(Throwable cause) {
        super("The model call failed.", cause);
    }
}
