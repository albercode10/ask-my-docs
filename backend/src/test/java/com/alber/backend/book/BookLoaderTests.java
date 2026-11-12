package com.alber.backend.book;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The book is read to clean text once, and a missing file means "no book" rather than a crash. */
class BookLoaderTests {

    @TempDir
    Path dir;

    @Test
    void loadsTheTextOfEveryChapterFromAnEpub() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("book.epub"));

        assertThat(BookLoader.load(epub))
                .hasValueSatisfying(text -> assertThat(text)
                        .contains(TestEpubs.CHAPTER_ONE_MARKER)
                        .contains(TestEpubs.CHAPTER_TWO_MARKER)
                        .contains("Grooming"));
    }

    @Test
    void cleanedTextHasNoMarkupOrRunsOfBlankLines() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("book.epub"));

        assertThat(BookLoader.load(epub))
                .hasValueSatisfying(text -> assertThat(text)
                        .doesNotContain("<p>", "<html", "margin:", "color: #333", ".jpg")
                        .doesNotContain("\n\n\n")
                        .doesNotMatch("(?s).*[ \\t]+\\n.*")
                        .doesNotMatch("(?s).*\\n[ \\t]+.*")
                        .isEqualTo(text.strip()));
    }

    @Test
    void chaptersComeInSpineOrderNotFileOrder() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("book.epub"));

        assertThat(BookLoader.load(epub))
                .hasValueSatisfying(text -> assertThat(text)
                        .containsSubsequence(TestEpubs.CHAPTER_ONE_MARKER, TestEpubs.CHAPTER_TWO_MARKER));
    }

    @Test
    void fileThatIsNotAnEpubGivesNoBookInsteadOfFailing() throws Exception {
        Path notAnEpub = Files.writeString(dir.resolve("book.epub"), "this is not a zip file");

        assertThat(BookLoader.load(notAnEpub)).isEmpty();
    }

    @Test
    void epubWithoutTextGivesNoBook() throws Exception {
        Path epub = TestEpubs.writeWithoutText(dir.resolve("book.epub"));

        assertThat(BookLoader.load(epub)).isEmpty();
    }

    @Test
    void missingFileGivesNoBookInsteadOfFailing() {
        assertThat(BookLoader.load(dir.resolve("does-not-exist.epub"))).isEmpty();
    }
}
