package com.alber.backend.chat;

/**
 * Drops qwen3's {@code <think>…</think>} reasoning from a streamed answer, piece by piece. A tag can be split across
 * pieces, so a piece that ends with the start of a tag ({@code <}, {@code <th}, …) is held back until the next piece
 * shows whether it is one. An unterminated block is dropped to the end, and the whitespace right after a block is
 * dropped, like {@link ChatService#stripThinking} does for {@code /chat}.
 *
 * <p>Stateful: one instance per stream, not thread-safe (a {@code Flux} delivers pieces one at a time).
 */
class ThinkingFilter {

    private static final String OPEN = "<think>";

    private static final String CLOSE = "</think>";

    private boolean thinking;

    /** Right after a {@code </think>}: whitespace is dropped until the first visible character. */
    private boolean afterBlock;

    /** Text held back because it might be the start of a tag. */
    private String pending = "";

    /** The visible part of this piece (possibly empty), plus any held-back text it releases. */
    String accept(String piece) {
        String text = pending + piece;
        pending = "";
        StringBuilder visible = new StringBuilder();
        while (true) {
            String tag = thinking ? CLOSE : OPEN;
            int at = text.indexOf(tag);
            if (at >= 0) {
                if (!thinking) {
                    appendVisible(visible, text.substring(0, at));
                }
                text = text.substring(at + tag.length());
                thinking = !thinking;
                afterBlock = !thinking;
                continue;
            }
            int held = partialTagAtEnd(text, tag);
            if (!thinking) {
                appendVisible(visible, text.substring(0, text.length() - held));
            }
            pending = text.substring(text.length() - held);
            return visible.toString();
        }
    }

    /** What's still held back at the end of the stream: kept if it's ordinary text, dropped inside reasoning. */
    String finish() {
        StringBuilder rest = new StringBuilder();
        if (!thinking) {
            appendVisible(rest, pending);
        }
        pending = "";
        return rest.toString();
    }

    private void appendVisible(StringBuilder visible, String text) {
        if (afterBlock) {
            text = text.stripLeading();
            afterBlock = text.isEmpty();
        }
        visible.append(text);
    }

    /** Length of the longest end of {@code text} that is a proper start of {@code tag}. */
    private static int partialTagAtEnd(String text, String tag) {
        for (int length = Math.min(text.length(), tag.length() - 1); length > 0; length--) {
            if (text.endsWith(tag.substring(0, length))) {
                return length;
            }
        }
        return 0;
    }
}
