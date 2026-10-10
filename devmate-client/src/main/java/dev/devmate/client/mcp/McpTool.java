package dev.devmate.client.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tool as discovered through {@code tools/list}.
 *
 * @param server      the configured name of the server that offers it, e.g. {@code files}
 * @param inputSchema JSON Schema of the arguments, as the server sent it
 * @param readOnly    the server declared {@code readOnlyHint=true}; DevMate only runs such tools
 */
public record McpTool(String server, String name, String description, Map<String, Object> inputSchema,
                      boolean readOnly) {

    public McpTool {
        inputSchema = inputSchema == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
        description = description == null ? "" : description;
    }

    /** Whether the arguments object has a property with this name. */
    @SuppressWarnings("unchecked")
    public boolean hasParameter(String parameter) {
        return inputSchema.get("properties") instanceof Map<?, ?> properties
                && ((Map<String, Object>) properties).containsKey(parameter);
    }
}
