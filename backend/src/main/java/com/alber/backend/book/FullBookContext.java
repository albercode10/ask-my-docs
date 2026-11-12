package com.alber.backend.book;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** Gives the model the whole book with every question. It fits in a 32k context window, so no retrieval yet. */
@Component
class FullBookContext implements BookContext {

    private final Optional<String> book;

    FullBookContext(BookProperties properties) {
        this.book = BookLoader.load(properties.file());
    }

    @Override
    public Optional<String> contextFor(String question) {
        return book;
    }
}
