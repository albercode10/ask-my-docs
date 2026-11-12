package com.alber.backend.chat;

import static org.mockito.Mockito.when;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/** Helpers for tests that stub Spring AI's {@code ChatModel}. */
final class ChatModelStubs {

    /** The context size the stubbed model reports as its default, like the real one configured in properties. */
    static final int DEFAULT_NUM_CTX = 32768;

    private ChatModelStubs() {}

    /**
     * Gives the mock default options, as the real Ollama model has. {@code ChatClient} builds every request's options
     * from them, and a mock would return null.
     */
    static void stubDefaultOptions(ChatModel chatModel) {
        when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder()
                        .model("qwen3:8b")
                        .numCtx(DEFAULT_NUM_CTX)
                        .build());
    }

    /** A model response whose only generation has the given text. */
    static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** The text of the prompt's message of the given type, joined if there are several. */
    static String textOf(Prompt prompt, MessageType type) {
        return prompt.getInstructions().stream()
                .filter(m -> m.getMessageType() == type)
                .map(Message::getText)
                .reduce("", String::concat);
    }
}
