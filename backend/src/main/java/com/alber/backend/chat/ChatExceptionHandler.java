package com.alber.backend.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ChatController.class)
class ChatExceptionHandler {

    @ExceptionHandler(BookUnavailableException.class)
    ProblemDetail bookUnavailable() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The book isn't loaded, so there's nothing to answer from. See README → The book.");
        problem.setTitle("Book not loaded");
        return problem;
    }

    @ExceptionHandler(ModelUnavailableException.class)
    ProblemDetail modelUnavailable() {
        // The cause is logged by ChatService; the client gets no internal details.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The model didn't answer. Check that Ollama is running with the configured model pulled.");
        problem.setTitle("Model unavailable");
        return problem;
    }
}
