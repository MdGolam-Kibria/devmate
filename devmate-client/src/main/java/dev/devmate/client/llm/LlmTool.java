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

    /**
     * What the tool returned, as text for the model.
     *
     * @param summary one line on the outcome for the user (counts, flags, or the error reason), never the content;
     *                null derives one: the error's first line, else the text's length
     */
    public record Output(String text, boolean error, String summary) {

        private static final int MAX_SUMMARY = 200;

        public Output {
            Objects.requireNonNull(text, "text");
            summary = summary == null || summary.isBlank() ? defaultSummary(text, error) : summary;
            if (summary.length() > MAX_SUMMARY) {
                summary = summary.substring(0, MAX_SUMMARY) + "…";
            }
        }

        public Output(String text, boolean error) {
            this(text, error, null);
        }

        private static String defaultSummary(String text, boolean error) {
            return error ? text.strip().lines().findFirst().orElse("error") : text.length() + " chars";
        }

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
