package com.alber.backend.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Chat settings under {@code app.chat}. ({@code app.chat.warm-up} is read by {@link ChatWarmUp}'s condition.)
 *
 * @param streamTimeout how long a streamed answer may take in total before it ends with an {@code error} event. A
 *     generous ceiling: a full-length answer (num-predict 1024 tokens) takes about a minute.
 */
@ConfigurationProperties("app.chat")
record ChatProperties(@DefaultValue("5m") Duration streamTimeout) {}
