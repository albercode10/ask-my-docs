package com.alber.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.alber.backend.book.KeywordEmbeddingModel;
import com.alber.backend.book.TestEpubs;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.client.ResourceAccessException;

/** When the book can't be indexed at startup (Ollama down), the app still starts and both endpoints answer 503. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatWithIndexingFailureTests {

    private static final String QUESTION = "{\"question\": \"When were waxed moustaches popular?\"}";

    @DynamicPropertySource
    static void bookPath(DynamicPropertyRegistry registry) {
        registry.add("app.book.path", () -> TestEpubs.writeToTempDir().toString());
    }

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ChatModel chatModel;

    @TestBean
    private EmbeddingModel embeddingModel;

    static EmbeddingModel embeddingModel() {
        return new KeywordEmbeddingModel()
                .failWhen(
                        text -> true,
                        text -> new ResourceAccessException(
                                "I/O error on POST request", new ConnectException("Connection refused")));
    }

    @Test
    void chatAndStreamAnswer503WithTheSameBodyAsWithoutABook() {
        MvcTestResult chat = post("/chat", MediaType.APPLICATION_JSON);
        MvcTestResult streamed = post("/chat/stream", MediaType.TEXT_EVENT_STREAM);

        assertThat(chat)
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(streamed).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ChatStreamEndpointTests.problemWithoutInstance(streamed))
                .isEqualTo(ChatStreamEndpointTests.problemWithoutInstance(chat))
                .containsEntry("title", "Book not loaded");
        assertThat((String) ChatStreamEndpointTests.problemWithoutInstance(chat).get("detail"))
                .contains("indexed");
        verify(chatModel, never()).call(any(Prompt.class));
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    private MvcTestResult post(String uri, MediaType accept) {
        return mvc.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(accept)
                .content(QUESTION)
                .exchange();
    }
}
