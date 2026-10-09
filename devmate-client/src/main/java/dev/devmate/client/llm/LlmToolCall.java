package dev.devmate.client.llm;

/**
 * One tool call the model made while answering, for display and tests. Never holds the tool's output, only its
 * one-line {@code summary} (see {@link LlmTool.Output#summary()}).
 */
public record LlmToolCall(String name, String arguments, boolean error, String summary, long durationMs) {}
