package com.alber.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Without the book file the app still starts, and /chat says the book isn't loaded. */
@SpringBootTest(properties = "app.book.path=target/no-such-dir/missing-book.epub")
@AutoConfigureMockMvc
class ChatWithoutBookTests {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ChatModel chatModel;

    @Test
    void appStartsAndChatReturns503WhenTheBookIsMissing() {
        assertThat(mvc.post()
                        .uri("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"What is a goatee?\"}"))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.title").isEqualTo("Book not loaded");
                    json.assertThat().extractingPath("$.detail").asString().containsIgnoringCase("book");
                });

        verify(chatModel, never()).call(any(Prompt.class));
    }
}
