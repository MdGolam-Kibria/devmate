package dev.devmate.client.llm;

/** The model could not produce an answer. The message is safe to show to users (no keys, no stack details). */
public final class LlmException extends RuntimeException {

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
