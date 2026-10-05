package dev.devmate.client.llm;

import java.util.Objects;
import java.util.function.Function;

/**
 * A tool the model may call, in DevMate's own terms (no provider or MCP types). The agent builds these from
 * dynamically discovered MCP tools; {@link LlmClient} adapters translate them for the provider.
 *
 * @param inputSchema JSON Schema of the arguments object, as a JSON string
 * @param handler     runs the tool for a JSON arguments string. Expected failures come back as an error
 *                    {@link Output} so the model can react; exceptions abort the whole answer.
 */
public record LlmTool(String name, String description, String inputSchema, Function<String, Output> handler) {

    /** What the tool returned, as text for the model. */
    public record Output(String text, boolean error) {

        public static Output ok(String text) {
            return new Output(text, false);
        }

        public static Output error(String text) {
            return new Output(text, true);
        }
    }

    public LlmTool {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(handler, "handler");
        description = description == null ? "" : description;
    }
}
