package com.alber.backend.chat;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Puts the book into Ollama's prompt cache right after startup, in the background, so the first question is fast.
 * Turned off with {@code app.chat.warm-up=false} (tests do). A failure only logs a warning.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("app.chat.warm-up")
public class ChatWarmUp {

    private final ChatService chatService;

    ChatWarmUp(ChatService chatService) {
        this.chatService = chatService;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        Thread.ofVirtual().name("chat-warm-up").start(this::run);
    }

    /** Runs the warm-up and never throws. */
    public void run() {
        long start = System.nanoTime();
        try {
            chatService.warmUp();
            log.info(
                    "Warmed up the model with the book in {} s",
                    Duration.ofNanos(System.nanoTime() - start).toSeconds());
        } catch (BookUnavailableException e) {
            log.info("No book loaded, so no warm-up.");
        } catch (RuntimeException e) {
            log.warn(
                    "Warm-up failed ({}). The first question will be slow. Is Ollama running with the model pulled?",
                    e.toString());
        }
    }
}
