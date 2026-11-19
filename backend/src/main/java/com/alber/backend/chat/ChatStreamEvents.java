package com.alber.backend.chat;

import java.util.Map;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Turns a streamed answer into the server-sent events of POST /chat/stream: {@code token} per piece, then
 * {@code done}, or one {@code error} with a fixed message when the answer fails or times out.
 */
@Slf4j
final class ChatStreamEvents {

    static final String MODEL_FAILED =
            "The model stopped before finishing the answer. Check that Ollama is running with the configured model pulled.";

    static final String TIMED_OUT = "The answer took longer than the time limit.";

    /** Data of a {@code token} event. */
    record Token(String text) {}

    /** Data of an {@code error} event. */
    record ErrorData(String message) {}

    private ChatStreamEvents() {}

    static Flux<ServerSentEvent<?>> of(Flux<String> answer) {
        Flux<ServerSentEvent<?>> tokens = answer.map(text -> event("token", new Token(text)));
        return tokens.concatWith(Flux.just(event("done", Map.of())))
                .onErrorResume(e -> Flux.just(event("error", new ErrorData(messageFor(e)))))
                // The client went away (or MVC gave up on the response); not an error.
                .doOnCancel(() -> log.debug("Streamed answer cancelled by the client."));
    }

    private static String messageFor(Throwable e) {
        // The cause stays in the log; the client gets a fixed message with no internal details.
        if (e instanceof TimeoutException) {
            log.warn("Streamed answer timed out (app.chat.stream-timeout).");
            return TIMED_OUT;
        }
        log.warn("Streamed answer failed: {}", e.toString());
        return MODEL_FAILED;
    }

    private static ServerSentEvent<?> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
