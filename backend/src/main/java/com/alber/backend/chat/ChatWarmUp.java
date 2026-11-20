package com.alber.backend.chat;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Loads the chat model into Ollama right after startup, in the background, so the first question doesn't wait for it.
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
                    "Warmed up the chat model in {} s",
                    Duration.ofNanos(System.nanoTime() - start).toSeconds());
        } catch (BookUnavailableException e) {
            log.info("The book isn't indexed, so no warm-up.");
        } catch (RuntimeException e) {
            log.warn(
                    "Warm-up failed ({}). The first question will be slow. Is Ollama running with the model pulled?",
                    e.toString());
        }
    }
}
