package com.alber.backend.book;

import org.jspecify.annotations.Nullable;

/**
 * One chapter of the book: its cleaned text and its title from the EPUB's table of contents.
 *
 * @param title the table-of-contents title, or null when the EPUB doesn't name this part of the book
 * @param text the chapter's cleaned text, never blank
 */
public record BookChapter(@Nullable String title, String text) {}
