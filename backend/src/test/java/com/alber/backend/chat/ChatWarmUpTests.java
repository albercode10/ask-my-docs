package com.alber.backend.chat;

import static com.alber.backend.chat.ChatModelStubs.answer;
import static com.alber.backend.chat.ChatModelStubs.stubDefaultOptions;
import static com.alber.backend.chat.ChatModelStubs.textOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alber.backend.book.TestEpubs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * At startup, one background request puts the book into Ollama's prompt cache, so the first real question doesn't
 * pay for processing the whole book.
 *
 * <p>The mocked model can't be stubbed before startup, so the startup trigger is checked on a spy of the service, and
 * the request itself by running the warm-up again once the model is stubbed. Each test gets a fresh context, since
 * the warm-up runs once per startup.
 */
@SpringBootTest(properties = "app.chat.warm-up=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ChatWarmUpTests {

    private static final int WAIT_MS = 10_000;

    @DynamicPropertySource
    static void bookPath(DynamicPropertyRegistry registry) {
        registry.add("app.book.path", () -> TestEpubs.writeToTempDir().toString());
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ChatWarmUp warmUp;

    @MockitoBean
    private ChatModel chatModel;

    @MockitoSpyBean
    private ChatService chatService;

    @Test
    void warmUpRunsOnceAtStartup() {
        verify(chatService, timeout(WAIT_MS).times(1)).warmUp();
    }

    @Test
    void warmUpSendsTheSameSystemPromptAsChat() {
        Prompt warmUpPrompt = runWarmUpAgainstAStubbedModel();

        when(chatModel.call(any(Prompt.class))).thenReturn(answer("An answer."));
        assertThat(mvc.post()
                        .uri("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"What is a goatee?\"}"))
                .hasStatusOk();

        Prompt chatPrompt = lastPromptWithQuestion("What is a goatee?");
        String warmUpSystem = textOf(warmUpPrompt, MessageType.SYSTEM);
        assertThat(warmUpSystem).contains(TestEpubs.CHAPTER_ONE_MARKER);
        // Byte-identical, so Ollama can reuse the cached prefix for the real question.
        assertThat(textOf(chatPrompt, MessageType.SYSTEM)).isEqualTo(warmUpSystem);
    }

    @Test
    void warmUpAsksForASingleTokenWithTheSameContextSize() {
        Prompt warmUpPrompt = runWarmUpAgainstAStubbedModel();

        assertThat(warmUpPrompt.getOptions()).isInstanceOfSatisfying(OllamaChatOptions.class, options -> {
            assertThat(options.getNumPredict()).isEqualTo(1);
            // A different context size would make Ollama reload the model and drop the cache.
            assertThat(options.getNumCtx()).isEqualTo(ChatModelStubs.DEFAULT_NUM_CTX);
        });
    }

    @Test
    void warmUpFailureDoesNotStopTheApp() {
        verify(chatService, timeout(WAIT_MS).times(1)).warmUp();
        stubDefaultOptions(chatModel);

        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("Ollama is not running"));
        assertThatNoException().isThrownBy(warmUp::run);

        when(chatModel.call(any(Prompt.class))).thenReturn(answer("Still answering."));
        assertThat(mvc.post()
                        .uri("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"What is a goatee?\"}"))
                .hasStatusOk();
    }

    /**
     * Waits for the startup warm-up to start, then runs it again with a stubbed model and returns the prompt it sent.
     * The startup run may still be in flight and reach the model too; picking prompts by their question, not by call
     * count, keeps the tests independent of that timing.
     */
    private Prompt runWarmUpAgainstAStubbedModel() {
        verify(chatService, timeout(WAIT_MS).times(1)).warmUp();
        stubDefaultOptions(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(answer("OK"));

        warmUp.run();

        return lastPromptWithQuestion(ChatService.WARM_UP_QUESTION);
    }

    private Prompt lastPromptWithQuestion(String question) {
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, atLeastOnce()).call(prompts.capture());
        List<Prompt> matching = prompts.getAllValues().stream()
                .filter(p -> textOf(p, MessageType.USER).equals(question))
                .toList();
        assertThat(matching).as("prompts asking %s", question).isNotEmpty();
        return matching.getLast();
    }
}
