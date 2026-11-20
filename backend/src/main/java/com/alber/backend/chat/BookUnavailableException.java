package com.alber.backend.chat;

/**
 * The book isn't loaded (missing or unreadable EPUB) or wasn't indexed at startup, so there's nothing to answer from.
 * Mapped to 503 by {@link ChatExceptionHandler}.
 */
class BookUnavailableException extends RuntimeException {

    BookUnavailableException() {
        super("The book isn't loaded or indexed.");
    }
}
