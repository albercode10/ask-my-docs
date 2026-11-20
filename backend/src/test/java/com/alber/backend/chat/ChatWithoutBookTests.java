package com.alber.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Without the book file the app still starts, and /chat and /chat/stream say the book isn't loaded. */
@SpringBootTest(properties = "app.book.path=target/no-such-dir/missing-book.epub")
@AutoConfigureMockMvc
class ChatWithoutBookTests {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @Test
    void noBookMeansTheEmbeddingModelIsNeverCalled() {
        assertThat(mvc.post()
                        .uri("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"What is a goatee?\"}"))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE);

        // Nothing indexed at startup, nothing embedded per question.
        verifyNoInteractions(embeddingModel);
    }

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

    @Test
    void streamReturns503WithTheSameBodyAsChatWhenTheBookIsMissing() {
        String question = "{\"question\": \"What is a goatee?\"}";
        MvcTestResult chat = mvc.post()
                .uri("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question)
                .exchange();
        MvcTestResult streamed = mvc.post()
                .uri("/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(question)
                .exchange();

        assertThat(streamed)
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(ChatStreamEndpointTests.problemWithoutInstance(streamed))
                .isEqualTo(ChatStreamEndpointTests.problemWithoutInstance(chat))
                .containsEntry("title", "Book not loaded");
        verify(chatModel, never()).stream(any(Prompt.class));
    }
}
