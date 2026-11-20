package com.alber.backend.book;

import org.jspecify.annotations.Nullable;

/**
 * A chunk of the book found for a question: what goes in the prompt, and what ALB-12 will show as a source.
 *
 * @param text the chunk's text, without the embedding task prefix
 * @param chapter its chapter title, or null when unknown
 * @param index its position in the book, counting from 0; the same on every start with the same book and settings
 * @param score cosine similarity to the question, higher is closer
 */
public record BookPassage(String text, @Nullable String chapter, int index, double score) {}
