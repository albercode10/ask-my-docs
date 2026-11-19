package com.alber.backend.chat;

import static com.alber.backend.chat.ChatModelStubs.answer;
import static com.alber.backend.chat.ChatModelStubs.pieces;
import static com.alber.backend.chat.ChatModelStubs.stubDefaultOptions;
import static com.alber.backend.chat.ChatModelStubs.textOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alber.backend.book.TestEpubs;
import com.alber.backend.chat.SseEvents.Event;
import com.jayway.jsonpath.JsonPath;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import reactor.core.publisher.Flux;

/** POST /chat/stream sends the answer as server-sent events: token…, then done, or error on failure. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatStreamEndpointTests {

    private static final String QUESTION = "{\"question\": \"How do I keep a moustache in shape?\"}";

    @DynamicPropertySource
    static void bookPath(DynamicPropertyRegistry registry) {
        registry.add("app.book.path", () -> TestEpubs.writeToTempDir().toString());
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ChatController chatController;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void stubModel() {
        stubDefaultOptions(chatModel);
    }

    @Test
    void streamsOneTokenEventPerPieceThenDone() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(pieces("Wax", " it", " daily."));

        MvcTestResult result = stream(QUESTION);

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
        List<Event> events = events(result);
        assertThat(SseEvents.names(events)).containsExactly("token", "token", "token", "done");
        assertThat(events.subList(0, 3)).extracting(e -> e.field("text")).containsExactly("Wax", " it", " daily.");
        assertThat(events.get(3).data()).isEqualTo("{}");
    }

    @Test
    void joinedTokenTextEqualsTheModelsPiecesExactly() {
        String[] modelPieces = {" Trim", " the édge\n", "\n“weekly”", "  with scissors.\n"};
        when(chatModel.stream(any(Prompt.class))).thenReturn(pieces(modelPieces));

        List<Event> events = events(stream(QUESTION));

        assertThat(SseEvents.joinedTokenText(events)).isEqualTo(String.join("", modelPieces));
        assertThat(SseEvents.names(events)).containsExactly("token", "token", "token", "token", "done");
    }

    @Test
    void emptyPiecesAreSkipped() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(pieces("", "Wax", "", " it.", ""));

        List<Event> events = events(stream(QUESTION));

        assertThat(SseEvents.names(events)).containsExactly("token", "token", "done");
        assertThat(SseEvents.joinedTokenText(events)).isEqualTo("Wax it.");
    }

    @Test
    void answerWithNoPiecesGivesDoneOnly() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.empty());

        assertThat(SseEvents.names(events(stream(QUESTION)))).containsExactly("done");
    }

    @Test
    void thinkBlockSplitAcrossPiecesNeverReachesAToken() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(pieces("<thi", "nk>\nThe user asks", " about wax.\n</th", "ink>\n\nWax", " it daily."));

        List<Event> events = events(stream(QUESTION));

        assertThat(events)
                .filteredOn(e -> e.name().equals("token"))
                .allSatisfy(e -> assertThat(e.field("text"))
                        .doesNotContain("think")
                        .doesNotContain("user asks")
                        .doesNotContain("<")
                        .doesNotContain(">"));
        assertThat(SseEvents.joinedTokenText(events)).isEqualTo("Wax it daily.");
        assertThat(SseEvents.names(events)).last().isEqualTo("done");
    }

    @Test
    void invalidQuestionsGive400WithTheSameBodyAsChat() {
        String overlong = "{\"question\": \"" + "a".repeat(ChatRequest.MAX_QUESTION_LENGTH + 1) + "\"}";
        for (String body : List.of("{\"question\": \"   \"}", "{}", "", "{\"question\": ", overlong)) {
            assertSameProblemAsChat(body, HttpStatus.BAD_REQUEST);
        }

        verify(chatModel, never()).stream(any(Prompt.class));
    }

    @Test
    void modelFailureBeforeAnyTokenGivesAnErrorEventAndNoDone() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new IllegalStateException("Connection refused: localhost:11434")));

        MvcTestResult result = stream(QUESTION);

        assertThat(result).hasStatusOk();
        List<Event> events = events(result);
        assertThat(SseEvents.names(events)).containsExactly("error");
        assertGenericErrorMessage(events.getFirst());
    }

    @Test
    void modelFailureAfterSomeTokensGivesThoseTokensThenAnErrorEvent() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(
                        pieces("Wax", " it"), Flux.error(new IllegalStateException("Connection reset by peer"))));

        List<Event> events = events(stream(QUESTION));

        assertThat(SseEvents.names(events)).containsExactly("token", "token", "error");
        assertThat(SseEvents.joinedTokenText(events)).isEqualTo("Wax it");
        assertGenericErrorMessage(events.getLast());
    }

    @Test
    void systemPromptContainsTheInstructionsAndTheBookText() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(pieces("An answer."));

        assertThat(stream("{\"question\": \"When were waxed moustaches popular?\"}"))
                .hasStatusOk();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompt.capture());
        assertThat(textOf(prompt.getValue(), MessageType.SYSTEM))
                .contains("only")
                .containsIgnoringCase("book")
                .contains(TestEpubs.CHAPTER_ONE_MARKER)
                .contains(TestEpubs.CHAPTER_TWO_MARKER);
        assertThat(textOf(prompt.getValue(), MessageType.USER)).isEqualTo("When were waxed moustaches popular?");
    }

    @Test
    void clientDisconnectCancelsTheModelStream() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<ChatResponse> endless = Flux.concat(Flux.just(answer("Wax")), Flux.<ChatResponse>never())
                .doOnCancel(() -> cancelled.set(true));
        when(chatModel.stream(any(Prompt.class))).thenReturn(endless);

        // What Spring MVC does when the client goes away: it cancels its subscription to the returned stream.
        chatController.stream(new ChatRequest("How do I keep a moustache in shape?"))
                .take(1)
                .blockLast(Duration.ofSeconds(5));

        assertThat(cancelled).isTrue();
    }

    private void assertSameProblemAsChat(String body, HttpStatus status) {
        MvcTestResult chat = post("/chat", body, MediaType.APPLICATION_JSON);
        MvcTestResult streamed = stream(body);

        assertThat(streamed).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(chat).hasStatus(status);
        assertThat(problemWithoutInstance(streamed))
                .as("problem for body %s", body)
                .isEqualTo(problemWithoutInstance(chat));
    }

    /** The problem detail's fields, minus {@code instance}: the request path, which differs by design. */
    static Map<String, Object> problemWithoutInstance(MvcTestResult result) {
        Map<String, Object> problem = new LinkedHashMap<>(JsonPath.read(body(result), "$"));
        problem.remove("instance");
        return problem;
    }

    private static void assertGenericErrorMessage(Event error) {
        assertThat(error.field("message"))
                .isNotBlank()
                .doesNotContain("Connection")
                .doesNotContain("localhost")
                .doesNotContain("Exception");
    }

    private MvcTestResult stream(String body) {
        return post("/chat/stream", body, MediaType.TEXT_EVENT_STREAM);
    }

    private MvcTestResult post(String uri, String body, MediaType accept) {
        return mvc.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(accept)
                .content(body)
                .exchange();
    }

    static List<Event> events(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return SseEvents.parse(body(result));
    }

    /** The response body as UTF-8, the encoding of server-sent events. */
    static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
