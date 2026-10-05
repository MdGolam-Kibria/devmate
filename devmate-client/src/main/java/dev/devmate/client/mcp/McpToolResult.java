package dev.devmate.client.mcp;

/** A {@code tools/call} result reduced to its text. {@code error} mirrors the server's {@code isError}. */
public record McpToolResult(String text, boolean error) {}
