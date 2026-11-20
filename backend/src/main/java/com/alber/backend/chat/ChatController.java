package com.alber.backend.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@Tag(name = "Chat", description = "Ask questions about the book")
class ChatController {

    private final ChatService chatService;

    ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(
            path = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Answer a question about the book",
            description = "Finds the passages of the book closest to the question (embeddings indexed at startup),"
                    + " sends them with the question to the local Ollama model and returns one complete answer. The"
                    + " model answers only from those passages, and says so when the book doesn't cover the"
                    + " question.")
    @ApiResponse(responseCode = "200", description = "The answer")
    @ApiResponse(
            responseCode = "400",
            description =
                    "The question is missing, blank or longer than " + ChatRequest.MAX_QUESTION_LENGTH + " characters",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "503",
            description = "The book isn't loaded (no EPUB at app.book.path) or wasn't indexed at startup, or the model"
                    + " didn't answer (Ollama down or a model not pulled)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ChatAnswer chat(@Valid @RequestBody ChatRequest request) {
        return new ChatAnswer(chatService.answer(request.question()));
    }

    @PostMapping(
            path = "/chat/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Stream the answer to a question about the book",
            description = "Same question and validation as POST /chat, but the answer arrives as server-sent events"
                    + " (UTF-8) while the model writes it: one `token` event per piece of the answer, data"
                    + " `{\"text\": \"...\"}`, in order; then one `done` event, data `{}`, and the stream closes."
                    + " If the model fails or the answer takes longer than app.chat.stream-timeout (default 5"
                    + " minutes) after the stream opened, one `error` event, data `{\"message\": \"...\"}`, ends the"
                    + " stream instead of `done`; so does a failure to embed the question. An answer with no visible"
                    + " text gives `done` with no `token` events.")
    @ApiResponse(
            responseCode = "200",
            description = "The answer as a stream of token events, then done (or error)",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
    @ApiResponse(
            responseCode = "400",
            description = "Same as POST /chat: the question is missing, blank or longer than "
                    + ChatRequest.MAX_QUESTION_LENGTH + " characters. No stream is opened.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "The book isn't loaded (no EPUB at app.book.path) or wasn't indexed at startup. No stream is opened.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    Flux<ServerSentEvent<?>> stream(@Valid @RequestBody ChatRequest request) {
        // Throws BookUnavailableException now, before the stream opens, so it becomes the same 503 as /chat.
        Flux<String> answer = chatService.stream(request.question());
        return ChatStreamEvents.of(answer);
    }
}
