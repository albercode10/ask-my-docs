package com.alber.backend.chat;

import com.alber.backend.book.BookContext;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;

/** Answers a question from the book through Spring AI's {@link ChatClient} and the configured Ollama model. */
@Slf4j
@Service
class ChatService {

    /** A {@code <think>} block, closed or cut off before its end. */
    private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?(?:</think>|\\z)");

    static final String WARM_UP_QUESTION = "Reply with OK.";

    private final ChatClient chatClient;

    private final BookContext bookContext;

    ChatService(ChatClient.Builder chatClientBuilder, BookContext bookContext) {
        this.chatClient = chatClientBuilder.build();
        this.bookContext = bookContext;
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
