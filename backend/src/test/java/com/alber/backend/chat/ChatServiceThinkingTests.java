package com.alber.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** qwen3's reasoning never reaches the answer, even when the model emits it although thinking is off. */
class ChatServiceThinkingTests {

    @Test
    void closedThinkBlockIsRemoved() {
        assertThat(ChatService.stripThinking("<think>\nreasoning\n</think>\n\nWax it daily."))
                .isEqualTo("Wax it daily.");
    }

    @Test
    void unterminatedThinkBlockIsRemovedToTheEnd() {
        assertThat(ChatService.stripThinking("Wax it daily.\n<think>\nreasoning cut off by the length cap"))
                .isEqualTo("Wax it daily.");
        assertThat(ChatService.stripThinking("<think>\nreasoning cut off by the length cap"))
                .isEmpty();
    }

    @Test
    void answerWithoutThinkingIsKeptAsItIs() {
        assertThat(ChatService.stripThinking("  Wax it daily.  ")).isEqualTo("Wax it daily.");
        assertThat(ChatService.stripThinking(null)).isEmpty();
    }
}
