package dev.devmate.client.llm;

import java.util.Objects;

/** One earlier message of the conversation, replayed to the model as context. Only what the user saw: no tool calls. */
public record LlmMessage(Role role, String text) {

    public enum Role {
        USER,
        ASSISTANT
    }

    public LlmMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
    }

    public static LlmMessage user(String text) {
        return new LlmMessage(Role.USER, text);
    }

    public static LlmMessage assistant(String text) {
        return new LlmMessage(Role.ASSISTANT, text);
    }
}
