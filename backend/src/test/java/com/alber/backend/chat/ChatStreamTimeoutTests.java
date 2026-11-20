package com.alber.backend.chat;

import static com.alber.backend.chat.ChatModelStubs.stubDefaultOptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.alber.backend.book.KeywordEmbeddingModel;
import com.alber.backend.book.TestEpubs;
import com.alber.backend.chat.SseEvents.Event;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import reactor.core.publisher.Flux;

/** A streamed answer that doesn't finish within app.chat.stream-timeout (in total) ends with an error event. */
@SpringBootTest(properties = "app.chat.stream-timeout=200ms")
@AutoConfigureMockMvc
class ChatStreamTimeoutTests {

    @DynamicPropertySource
    static void bookPath(DynamicPropertyRegistry registry) {
        registry.add("app.book.path", () -> TestEpubs.writeToTempDir().toString());
    }

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ChatModel chatModel;

    @TestBean(methodName = KeywordEmbeddingModel.FACTORY)
    private EmbeddingModel embeddingModel;

    @Test
    void answerThatDoesNotFinishInTimeGivesAnErrorEventAndCancelsTheModel() {
        stubDefaultOptions(chatModel);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(ChatModelStubs.pieces("Wax"), Flux.<ChatResponse>never())
                        .doOnCancel(() -> cancelled.set(true)));

        List<Event> events = ChatStreamEndpointTests.events(mvc.post()
                .uri("/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"question\": \"How do I keep a moustache in shape?\"}")
                .exchange());

        assertThat(SseEvents.names(events)).containsExactly("token", "error");
        assertThat(events.getLast().field("message")).isNotBlank().doesNotContain("Exception");
        assertThat(cancelled).isTrue();
    }

    @Test
    void answerThatKeepsTricklingPastTheLimitStillEndsWithAnErrorEvent() {
        stubDefaultOptions(chatModel);
        AtomicBoolean cancelled = new AtomicBoolean();
        // A piece every 50 ms: no gap ever reaches the 200 ms limit, but the whole answer does.
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.interval(Duration.ofMillis(50))
                        .map(i -> ChatModelStubs.answer("x"))
                        .doOnCancel(() -> cancelled.set(true)));

        List<Event> events = ChatStreamEndpointTests.events(mvc.post()
                .uri("/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"question\": \"How do I keep a moustache in shape?\"}")
                .exchange(Duration.ofSeconds(5)));

        assertThat(SseEvents.names(events)).contains("token").last().isEqualTo("error");
        assertThat(events.getLast().field("message")).isEqualTo(ChatStreamEvents.TIMED_OUT);
        assertThat(cancelled).isTrue();
    }
}
