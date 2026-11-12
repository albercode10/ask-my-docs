package com.alber.backend.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
            description = "Sends the question, with the whole book as context, to the local Ollama model and returns"
                    + " one complete answer. The model answers only from the book, and says so when the book doesn't"
                    + " cover the question.")
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
            description =
                    "The book isn't loaded (no EPUB at app.book.path), or the model didn't answer (Ollama down or the model not pulled)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ChatAnswer chat(@Valid @RequestBody ChatRequest request) {
        return new ChatAnswer(chatService.answer(request.question()));
    }
}
