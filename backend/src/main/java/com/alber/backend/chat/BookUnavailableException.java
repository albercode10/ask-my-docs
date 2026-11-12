package com.alber.backend.chat;

/** No book is loaded, so there's nothing to answer from. Mapped to 503 by {@link ChatExceptionHandler}. */
class BookUnavailableException extends RuntimeException {

    BookUnavailableException() {
        super("The book isn't loaded.");
    }
}
