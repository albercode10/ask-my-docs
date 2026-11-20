package com.alber.backend.book;

import org.jspecify.annotations.Nullable;

/**
 * A piece of one chapter, as indexed.
 *
 * @param text the chunk's text
 * @param chapter its chapter title, or null when unknown
 * @param index its position in the book, counting from 0
 */
record BookChunk(String text, @Nullable String chapter, int index) {}
