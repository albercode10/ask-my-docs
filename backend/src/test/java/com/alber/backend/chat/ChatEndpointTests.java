package com.alber.backend.chat;

import static com.alber.backend.chat.ChatModelStubs.answer;
import static com.alber.backend.chat.ChatModelStubs.stubDefaultOptions;
import static com.alber.backend.chat.ChatModelStubs.textOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alber.backend.book.TestEpubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** POST /chat sends the question with the whole book to the model and returns its answer. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatEndpointTests {

    @DynamicPropertySource
    static void bookPath(DynamicPropertyRegistry registry) {
        registry.add("app.book.path", () -> TestEpubs.writeToTempDir().toString());
    }

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void stubModel() {
        stubDefaultOptions(chatModel);
    }

    @Test
    void validQuestionReturns200WithTheModelsAnswer() {
        when(chatModel.call(any(Prompt.class))).thenReturn(answer("Wax it daily."));

        assertThat(ask("{\"question\": \"How do I keep a moustache in shape?\"}"))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .extractingPath("$.answer")
                .isEqualTo("Wax it daily.");
    }

    @Test
    void blankQuestionReturns400AndDoesNotCallTheModel() {
        assertThat(ask("{\"question\": \"   \"}")).hasStatus(HttpStatus.BAD_REQUEST);

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void missingQuestionReturns400() {
        assertThat(ask("{}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(ask("")).hasStatus(HttpStatus.BAD_REQUEST);

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void overlongQuestionReturns400AndDoesNotCallTheModel() {
        String question = "a".repeat(ChatRequest.MAX_QUESTION_LENGTH + 1);

        assertThat(ask("{\"question\": \"" + question + "\"}")).hasStatus(HttpStatus.BAD_REQUEST);

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void questionAtTheLengthLimitIsAnswered() {
        when(chatModel.call(any(Prompt.class))).thenReturn(answer("An answer."));
        String question = "a".repeat(ChatRequest.MAX_QUESTION_LENGTH);

        assertThat(ask("{\"question\": \"" + question + "\"}")).hasStatusOk();
    }

    @Test
    void modelFailureReturns503ProblemDetail() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("Connection refused"));

        assertThat(ask("{\"question\": \"How do I keep a moustache in shape?\"}"))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.title").isEqualTo("Model unavailable");
                    // The upstream error stays in the log, not in the response.
                    json.assertThat().extractingPath("$.detail").asString().doesNotContain("Connection refused");
                });
    }

    @Test
    void blankModelAnswerReturns503() {
        when(chatModel.call(any(Prompt.class))).thenReturn(answer("<think>\nOnly reasoning, no answer.\n</think>"));

        assertThat(ask("{\"question\": \"How do I keep a moustache in shape?\"}"))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Model unavailable");
    }

    @Test
    void systemPromptContainsTheInstructionsAndTheBookText() {
        when(chatModel.call(any(Prompt.class))).thenReturn(answer("An answer."));

        assertThat(ask("{\"question\": \"When were waxed moustaches popular?\"}"))
                .hasStatusOk();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(textOf(prompt.getValue(), MessageType.SYSTEM))
                .contains("only")
                .containsIgnoringCase("book")
                .contains(TestEpubs.CHAPTER_ONE_MARKER)
                // Curly braces in the book reach the model as they are, not as template variables.
                .contains(TestEpubs.CHAPTER_TWO_MARKER);
        assertThat(textOf(prompt.getValue(), MessageType.USER)).isEqualTo("When were waxed moustaches popular?");
    }

    @Test
    void thinkBlockIsStrippedFromTheAnswer() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(answer("<think>\nThe user asks about wax.\n</think>\n\nWax it daily."));

        assertThat(ask("{\"question\": \"How do I keep a moustache in shape?\"}"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.answer")
                .isEqualTo("Wax it daily.");
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult ask(String body) {
        return mvc.post()
                .uri("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }
}
