package dev.devmate.client.llm;

/** One tool call the model made while answering, for display and tests. Never holds the tool's output. */
public record LlmToolCall(String name, String arguments, boolean error) {}
