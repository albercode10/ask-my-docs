package com.alber.backend.chat;

/**
 * The system prompt: fixed instructions first, then the book. Every request starts with the same text, so Ollama can
 * reuse its prompt cache and only processes the question.
 */
final class BookPrompt {

    private static final String INSTRUCTIONS = """
            You answer questions about one book. Its full text is below, between <book> and </book>.
            Answer only from the book. Don't use outside knowledge, and don't make anything up.
            If the book doesn't contain the answer, say that the book doesn't cover it.
            Answer in the language of the question, clearly and concisely.
            """;

    private BookPrompt() {}

    /** Built by concatenation, not a template, so braces or angle brackets in the book reach the model unchanged. */
    static String system(String book) {
        return INSTRUCTIONS + "\n<book>\n" + book + "\n</book>\n";
    }
}
