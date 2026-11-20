package com.alber.backend.book;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The book is read once into chapters of clean text, in reading order, each with its table-of-contents title. A
 * missing or broken file means "no book" rather than a crash.
 */
class BookLoaderTests {

    @TempDir
    Path dir;

    @Test
    void readsEachChapterInSpineOrderWithItsTocTitle() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("book.epub"));

        List<BookChapter> chapters = BookLoader.loadChapters(epub).orElseThrow();

        assertThat(chapters)
                .extracting(BookChapter::title)
                .containsExactly(TestEpubs.CHAPTER_ONE_TITLE, TestEpubs.CHAPTER_TWO_TITLE);
        assertThat(chapters.get(0).text())
                .contains(TestEpubs.CHAPTER_ONE_MARKER)
                .doesNotContain(TestEpubs.CHAPTER_TWO_MARKER);
        assertThat(chapters.get(1).text())
                .contains(TestEpubs.CHAPTER_TWO_MARKER)
                .contains("Grooming")
                .doesNotContain(TestEpubs.CHAPTER_ONE_MARKER);
    }

    @Test
    void spineFileMissingFromTheTocContinuesThePreviousChapter() throws Exception {
        Path epub = TestEpubs.writeWithFrontMatterAndContinuation(dir.resolve("book.epub"));

        List<BookChapter> chapters = BookLoader.loadChapters(epub).orElseThrow();

        // Front matter before the first entry has no title; the second file of chapter one keeps chapter one's.
        assertThat(chapters)
                .extracting(BookChapter::title)
                .containsExactly(
                        null, TestEpubs.CHAPTER_ONE_TITLE, TestEpubs.CHAPTER_ONE_TITLE, TestEpubs.CHAPTER_TWO_TITLE);
        assertThat(chapters.get(0).text()).contains(TestEpubs.FRONT_MATTER_MARKER);
        assertThat(chapters.get(2).text()).contains(TestEpubs.CONTINUATION_MARKER);
    }

    @Test
    void epubWithoutTocGivesChaptersWithoutTitles() throws Exception {
        Path epub = TestEpubs.writeWithoutToc(dir.resolve("book.epub"));

        List<BookChapter> chapters = BookLoader.loadChapters(epub).orElseThrow();

        assertThat(chapters)
                .hasSize(2)
                .allSatisfy(chapter -> assertThat(chapter.title()).isNull());
        assertThat(chapters.get(0).text()).contains(TestEpubs.CHAPTER_ONE_MARKER);
    }

    @Test
    void tocWithExternalEntityIsNotResolved() throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "top-secret-value");
        Path epub = TestEpubs.writeWithExternalEntityInToc(dir.resolve("book.epub"), secret);

        List<BookChapter> chapters = BookLoader.loadChapters(epub).orElseThrow();

        assertThat(chapters)
                .hasSize(2)
                .noneSatisfy(chapter -> assertThat(chapter.title()).contains("top-secret-value"))
                .noneSatisfy(chapter -> assertThat(chapter.text()).contains("top-secret-value"));
    }

    @Test
    void cleanedTextHasNoMarkupOrRunsOfBlankLines() throws Exception {
        Path epub = TestEpubs.write(dir.resolve("book.epub"));

        assertThat(BookLoader.loadChapters(epub).orElseThrow())
                .isNotEmpty()
                .allSatisfy(chapter -> assertThat(chapter.text())
                        .doesNotContain("<p>", "<html", "margin:", "color: #333", ".jpg")
                        .doesNotContain("\n\n\n")
                        .doesNotMatch("(?s).*[ \\t]+\\n.*")
                        .doesNotMatch("(?s).*\\n[ \\t]+.*")
                        .isEqualTo(chapter.text().strip()));
    }

    @Test
    void fileThatIsNotAnEpubGivesNoBookInsteadOfFailing() throws Exception {
        Path notAnEpub = Files.writeString(dir.resolve("book.epub"), "this is not a zip file");

        assertThat(BookLoader.loadChapters(notAnEpub)).isEmpty();
    }

    @Test
    void epubWithoutTextGivesNoBook() throws Exception {
        Path epub = TestEpubs.writeWithoutText(dir.resolve("book.epub"));

        assertThat(BookLoader.loadChapters(epub)).isEmpty();
    }

    @Test
    void missingFileGivesNoBookInsteadOfFailing() {
        assertThat(BookLoader.loadChapters(dir.resolve("does-not-exist.epub"))).isEmpty();
    }
}
