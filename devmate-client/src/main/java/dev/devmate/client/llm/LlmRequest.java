package dev.devmate.client.llm;

import java.util.List;
import java.util.Objects;

/**
 * A single-turn request. {@code systemPrompt} may be null. With {@code tools}, the model may call them for up to
 * {@code maxToolRounds} rounds before it must answer.
 */
public record LlmRequest(String systemPrompt, String userMessage, List<LlmTool> tools, int maxToolRounds) {

    /** Longest user message accepted from the API or the UI. */
    public static final int MAX_USER_MESSAGE_LENGTH = 8000;

    public LlmRequest {
        Objects.requireNonNull(userMessage, "userMessage");
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (!tools.isEmpty() && maxToolRounds < 1) {
            throw new IllegalArgumentException("maxToolRounds must be >= 1 when tools are given");
        }
    }

    /** A request without tools. */
    public LlmRequest(String systemPrompt, String userMessage) {
        this(systemPrompt, userMessage, List.of(), 0);
    }

    public static LlmRequest of(String userMessage) {
        return new LlmRequest(null, userMessage);
    }
}
