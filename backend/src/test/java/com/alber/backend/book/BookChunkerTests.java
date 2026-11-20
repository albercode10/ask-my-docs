package com.alber.backend.book;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Chapters become chunks of about the configured size, numbered in book order, each tagged with its chapter. */
class BookChunkerTests {

    private static final List<BookChapter> CHAPTERS = List.of(
            new BookChapter(null, "Front matter. " + sentences("front", 5)),
            new BookChapter("First", sentences("alpha", 400)),
            new BookChapter("Second", sentences("beta", 400)));

    @Test
    void chunksNeverCrossChapters() {
        List<BookChunk> chunks = BookChunker.chunk(CHAPTERS, 100);

        assertThat(chunks).allSatisfy(chunk -> {
            boolean alpha = chunk.text().contains("alpha");
            boolean beta = chunk.text().contains("beta");
            assertThat(alpha && beta)
                    .as("chunk %d mixes chapters", chunk.index())
                    .isFalse();
        });
    }

    @Test
    void everyChunkCarriesItsChapterTitleOrNone() {
        List<BookChunk> chunks = BookChunker.chunk(CHAPTERS, 100);

        assertThat(chunks)
                .filteredOn(c -> c.text().contains("front"))
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.chapter()).isNull());
        assertThat(chunks)
                .filteredOn(c -> c.text().contains("alpha"))
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.chapter()).isEqualTo("First"));
        assertThat(chunks)
                .filteredOn(c -> c.text().contains("beta"))
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.chapter()).isEqualTo("Second"));
    }

    @Test
    void indexesCountFromZeroInBookOrderAndAreStable() {
        List<BookChunk> chunks = BookChunker.chunk(CHAPTERS, 100);

        assertThat(chunks)
                .extracting(BookChunk::index)
                .containsExactlyElementsOf(
                        IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks.getFirst().text()).contains("Front matter");
        assertThat(chunks.getLast().text()).contains("beta");
        // Same input, same chunks: the indexes mean the same passages on every start.
        assertThat(BookChunker.chunk(CHAPTERS, 100)).isEqualTo(chunks);
    }

    @Test
    void longChapterIsSplitIntoChunksNoLongerThanTheChunkSize() {
        List<BookChunk> chunks = BookChunker.chunk(CHAPTERS, 100);

        assertThat(chunks.stream().filter(c -> c.text().contains("alpha")).count())
                .isGreaterThan(5);
        // ~100 tokens of short English words is well under 1,000 characters.
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThan(1_000));
    }

    private static String sentences(String word, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("The ")
                    .append(word)
                    .append(" sentence number ")
                    .append(i)
                    .append(" ends here. ");
        }
        return text.toString().strip();
    }
}
