package com.alber.backend.book;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * Splits the book's chapters into chunks of about {@code chunkSize} tokens with Spring AI's {@link TokenTextSplitter}
 * (CL100K tokens, cut at the end of a sentence where it can). Each chapter is split on its own, so a chunk never mixes
 * two chapters, and chunks are numbered from 0 in book order: the same book and settings give the same chunks.
 */
final class BookChunker {

    private BookChunker() {}

    static List<BookChunk> chunk(List<BookChapter> chapters, int chunkSize) {
        TokenTextSplitter splitter =
                TokenTextSplitter.builder().withChunkSize(chunkSize).build();
        List<BookChunk> chunks = new ArrayList<>();
        for (BookChapter chapter : chapters) {
            for (Document piece : splitter.split(new Document(chapter.text()))) {
                String text = piece.getText();
                if (text != null && !text.isBlank()) {
                    chunks.add(new BookChunk(text.strip(), chapter.title(), chunks.size()));
                }
            }
        }
        return List.copyOf(chunks);
    }
}
