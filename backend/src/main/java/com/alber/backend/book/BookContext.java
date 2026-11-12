package com.alber.backend.book;

import java.util.Optional;

/**
 * The book text to give the model for a question. Today it's the whole book ({@link FullBookContext}); retrieval of
 * the most relevant passages (ALB-32) replaces the implementation without changing the endpoint.
 */
public interface BookContext {

    /** The context for this question, or empty when no book is loaded. */
    Optional<String> contextFor(String question);
}
