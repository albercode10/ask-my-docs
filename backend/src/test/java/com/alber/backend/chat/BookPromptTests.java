package com.alber.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.alber.backend.book.BookPassage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The system prompt: the same instructions first every time, then the passages found for the question. */
class BookPromptTests {

    @Test
    void instructionsComeFirstThenEachPassageWithItsChapter() {
        String prompt = BookPrompt.system(List.of(
                new BookPassage("Wax it daily.", "Moustaches", 3, 0.8),
                new BookPassage("Untitled front matter.", null, 0, 0.6)));

        assertThat(prompt)
                .startsWith(BookPrompt.instructions())
                .contains("only")
                .containsIgnoringCase("book")
                .containsSubsequence("<passage>", "Moustaches", "Wax it daily.", "</passage>")
                .containsSubsequence("Wax it daily.", "<passage>", "Untitled front matter.", "</passage>")
                .doesNotContain("null");
    }

    @Test
    void noPassagesGivesTheNoMatchLine() {
        String prompt = BookPrompt.system(List.of());

        assertThat(prompt).startsWith(BookPrompt.instructions());
        // The instructions name the <passage> tag; what follows them has none.
        assertThat(prompt.substring(BookPrompt.instructions().length()))
                .doesNotContain("<passage>")
                .containsIgnoringCase("no passage");
    }

    @Test
    void bracesInPassagesReachThePromptUnchanged() {
        String prompt = BookPrompt.system(List.of(new BookPassage("Trim {weekly} <now>", "Grooming", 1, 0.9)));

        assertThat(prompt).contains("Trim {weekly} <now>");
    }
}
