package com.alber.backend.chat;

import com.alber.backend.book.BookPassage;
import java.util.List;

/**
 * The system prompt: fixed instructions first, then the passages of the book found for the question. Every request
 * starts with the same instructions, so Ollama can reuse that part of its prompt cache.
 */
final class BookPrompt {

    private static final String INSTRUCTIONS = """
            You answer questions about one book. The passages of the book that are closest to the question are below,
            each between <passage> and </passage>, with the chapter it comes from.
            Answer only from these passages. Don't use outside knowledge, and don't make anything up.
            If the passages don't contain the answer, say that the book doesn't cover it.
            Answer in the language of the question, clearly and concisely.
            """;

    private static final String NO_PASSAGES = "No passage of the book is close to this question.\n";

    private BookPrompt() {}

    /** The instructions alone, the start of every system prompt. */
    static String instructions() {
        return INSTRUCTIONS;
    }

    /**
     * The instructions, then each passage with its chapter, best first. Built by concatenation, not a template, so
     * braces or angle brackets in the book reach the model unchanged.
     */
    static String system(List<BookPassage> passages) {
        StringBuilder prompt = new StringBuilder(INSTRUCTIONS).append('\n');
        if (passages.isEmpty()) {
            return prompt.append(NO_PASSAGES).toString();
        }
        for (BookPassage passage : passages) {
            prompt.append("<passage>\n");
            if (passage.chapter() != null) {
                prompt.append("Chapter: ").append(passage.chapter()).append("\n\n");
            }
            prompt.append(passage.text()).append("\n</passage>\n");
        }
        return prompt.toString();
    }
}
