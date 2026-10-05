package dev.devmate.client.llm;

import java.util.List;

/**
 * The model's answer. {@code model} is what the provider reports it actually used; token counts are summed over all
 * tool rounds and are null when the provider doesn't report them. {@code toolCalls} lists the calls in order.
 */
public record LlmResponse(String text, String model, Integer inputTokens, Integer outputTokens,
                          List<LlmToolCall> toolCalls) {

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** An answer that used no tools. */
    public LlmResponse(String text, String model, Integer inputTokens, Integer outputTokens) {
        this(text, model, inputTokens, outputTokens, List.of());
    }
}
