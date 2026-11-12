package com.alber.backend.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** With no book there is nothing to warm up, so the model isn't called at startup. */
@SpringBootTest(properties = {"app.chat.warm-up=true", "app.book.path=target/no-such-dir/missing-book.epub"})
class ChatWarmUpWithoutBookTests {

    @MockitoBean
    private ChatModel chatModel;

    @Test
    void noWarmUpCallWhenTheBookIsMissing() {
        verify(chatModel, after(1_000).never()).call(any(Prompt.class));
    }
}
