package com.alber.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** ThinkingFilter drops qwen3's <think> reasoning from a stream of pieces, even when a tag is split across pieces. */
class ThinkingFilterTests {

    @Test
    void textWithoutTagsPassesThroughPieceByPiece() {
        ThinkingFilter filter = new ThinkingFilter();

        assertThat(filter.accept("Wax")).isEqualTo("Wax");
        assertThat(filter.accept(" it 2 > 1\n")).isEqualTo(" it 2 > 1\n");
        assertThat(filter.finish()).isEmpty();
    }

    @Test
    void closedBlockInOnePieceIsDropped() {
        assertThat(filtered("<think>reasoning</think>Wax it.")).isEqualTo("Wax it.");
    }

    @Test
    void tagsSplitAcrossPiecesAreDropped() {
        assertThat(filtered("<", "thi", "nk>reason", "ing</", "think", ">Wax", " it."))
                .isEqualTo("Wax it.");
    }

    @Test
    void whitespaceRightAfterABlockIsDroppedLikeChatDoes() {
        ThinkingFilter filter = new ThinkingFilter();

        assertThat(filter.accept("<think>reasoning</think>\n")).isEmpty();
        assertThat(filter.accept("\n  ")).isEmpty();
        assertThat(filter.accept(" Wax\n it.")).isEqualTo("Wax\n it.");
        assertThat(filter.accept(" Then comb.")).isEqualTo(" Then comb.");
    }

    @Test
    void leadingWhitespaceWithoutABlockIsKept() {
        assertThat(filtered("  Wax", " it.")).isEqualTo("  Wax it.");
    }

    @Test
    void unterminatedBlockIsDroppedToTheEnd() {
        assertThat(filtered("Wax it.", "<think>reasoning cut off")).isEqualTo("Wax it.");
        // A partial closing tag held back at the end stays inside the block.
        assertThat(filtered("Wax it.", "<think>reasoning</th")).isEqualTo("Wax it.");
    }

    @Test
    void heldBackAngleBracketThatIsNotATagIsReleased() {
        ThinkingFilter filter = new ThinkingFilter();

        assertThat(filter.accept("Use 1 <")).isEqualTo("Use 1 ");
        assertThat(filter.accept(" 2 combs")).isEqualTo("< 2 combs");
        assertThat(filter.accept(" <th")).isEqualTo(" ");
        assertThat(filter.finish()).isEqualTo("<th");
    }

    private static String filtered(String... pieces) {
        ThinkingFilter filter = new ThinkingFilter();
        return Stream.concat(Stream.of(pieces).map(filter::accept), Stream.of(filter.finish()))
                .collect(Collectors.joining());
    }
}
