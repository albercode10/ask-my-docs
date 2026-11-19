package com.alber.backend.chat;

import com.alber.backend.book.BookContext;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Answers a question from the book through Spring AI's {@link ChatClient} and the configured Ollama model. */
@Slf4j
@Service
class ChatService {

    /** A {@code <think>} block, closed or cut off before its end. */
    private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?(?:</think>|\\z)");

    static final String WARM_UP_QUESTION = "Reply with OK.";

    private final ChatClient chatClient;

    private final BookContext bookContext;

    private final Duration streamTimeout;

    ChatService(ChatClient.Builder chatClientBuilder, BookContext bookContext, ChatProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.bookContext = bookContext;
        this.streamTimeout = properties.streamTimeout();
    }

    /**
     * The model's answer, grounded in the book.
     *
     * @throws BookUnavailableException when no book is loaded
     * @throws ModelUnavailableException when the model call fails or returns no answer
     */
    String answer(String question) {
        ChatClient.ChatClientRequestSpec request = prompt(question);
        ChatResponse response;
        try {
            response = request.call().chatResponse();
        } catch (RuntimeException e) {
            log.warn("Model call failed: {}", e.toString());
            throw new ModelUnavailableException(e);
        }
        Generation result = response == null ? null : response.getResult();
        String answer = stripThinking(result == null ? null : result.getOutput().getText());
        if (answer.isEmpty()) {
            log.warn("The model returned no answer (empty, or only reasoning).");
            throw new ModelUnavailableException("The model returned no answer.");
        }
        if ("length".equalsIgnoreCase(result.getMetadata().getFinishReason())) {
            // Still useful, so it's returned; the log says why it ends abruptly.
            log.info("The answer reached the num-predict cap and was cut off.");
        }
        return answer;
    }

    /**
     * The model's answer as it's generated, piece by piece, exactly as the model wrote it (no trimming). Empty pieces
     * and {@code <think>} reasoning (with the whitespace right after it) are dropped. An answer with no visible text
     * gives an empty stream, where {@link #answer} throws. The stream fails with a {@link TimeoutException} when the whole answer
     * takes longer than {@code app.chat.stream-timeout}, and with the model's error when the model call fails.
     * Cancelling it cancels the model call.
     *
     * @throws BookUnavailableException right away, before any stream exists, when no book is loaded
     */
    Flux<String> stream(String question) {
        ChatClient.ChatClientRequestSpec request = prompt(question);
        Flux<String> pieces = request.stream()
                .chatResponse()
                .doOnNext(ChatService::logIfCutOff)
                .mapNotNull(response -> response.getResult() == null
                        ? null
                        : response.getResult().getOutput().getText());
        return Flux.defer(() -> {
            ThinkingFilter filter = new ThinkingFilter();
            // One deadline for the whole answer, started at subscription (plain timeout(Duration) would only limit
            // the gap between pieces). When the deadline fires, takeUntilOther cancels the model stream and
            // completes, and the TimeoutException follows; when the answer ends first, it cancels the timer.
            // (A deadline that signals an error instead would not cancel the model stream.)
            AtomicBoolean timedOut = new AtomicBoolean();
            Mono<Long> deadline = Mono.delay(streamTimeout).doOnNext(tick -> timedOut.set(true));
            return pieces.map(filter::accept)
                    .concatWith(Mono.fromSupplier(filter::finish))
                    .filter(piece -> !piece.isEmpty())
                    .takeUntilOther(deadline)
                    .concatWith(Mono.defer(() -> timedOut.get() ? Mono.error(new TimeoutException()) : Mono.empty()));
        });
    }

    private static void logIfCutOff(ChatResponse response) {
        Generation result = response.getResult();
        if (result != null && "length".equalsIgnoreCase(result.getMetadata().getFinishReason())) {
            log.info("The streamed answer reached the num-predict cap and was cut off.");
        }
    }

    /**
     * Sends the system prompt once with a one-token reply, so Ollama caches the processed book and the next question
     * only pays for itself. Leaves the context size alone: a different {@code num_ctx} would reload the model.
     *
     * @throws BookUnavailableException when no book is loaded
     */
    void warmUp() {
        prompt(WARM_UP_QUESTION)
                .options(OllamaChatOptions.builder().numPredict(1))
                .call()
                .content();
    }

    private ChatClient.ChatClientRequestSpec prompt(String question) {
        String book = bookContext.contextFor(question).orElseThrow(BookUnavailableException::new);
        return chatClient.prompt().system(BookPrompt.system(book)).user(question);
    }

    /** Drops qwen3's {@code <think>} reasoning, in case the model emits it although thinking is off. */
    static String stripThinking(String answer) {
        return answer == null ? "" : THINKING.matcher(answer).replaceAll("").strip();
    }
}
