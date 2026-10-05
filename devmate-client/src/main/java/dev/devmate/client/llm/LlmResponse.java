package dev.devmate.client.llm;

/**
 * The model's answer. {@code model} is what the provider reports it actually used; token counts are null when the
 * provider doesn't report them.
 */
public record LlmResponse(String text, String model, Integer inputTokens, Integer outputTokens) {}
