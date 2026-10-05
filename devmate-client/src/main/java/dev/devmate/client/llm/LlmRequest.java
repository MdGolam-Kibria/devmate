package dev.devmate.client.llm;

import java.util.Objects;

/** A single-turn request. {@code systemPrompt} may be null. Tool definitions are added in M3. */
public record LlmRequest(String systemPrompt, String userMessage) {

    public LlmRequest {
        Objects.requireNonNull(userMessage, "userMessage");
    }

    public static LlmRequest of(String userMessage) {
        return new LlmRequest(null, userMessage);
    }
}
