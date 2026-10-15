package dev.devmate.client.agent;

import dev.devmate.client.llm.LlmTool;
import dev.devmate.client.mcp.McpClientService;
import dev.devmate.client.mcp.McpTool;
import dev.devmate.client.mcp.McpToolResult;
import java.util.Map;
import java.util.StringJoiner;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a discovered {@link McpTool} into an {@link LlmTool} the model can call: the server's name, description and
 * input schema as they are, and a handler that applies the question's scope, calls the tool through
 * {@link McpClientService}, cuts long output and summarizes the outcome for the user.
 */
final class McpToolBridge {

    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {};

    private final McpClientService mcp;
    private final AgentProperties limits;
    private final JsonMapper json;

    McpToolBridge(McpClientService mcp, AgentProperties limits, JsonMapper json) {
        this.mcp = mcp;
        this.limits = limits;
        this.json = json;
    }

    /**
     * @param scope the open folder the tool's {@code path} is pinned to, or null to pass arguments through unchanged
     *              (the server's own sandbox is then the only boundary)
     */
    LlmTool toLlmTool(McpTool tool, FolderScope scope) {
        // The server's own wording ("relative to the first allowed root", "omit to list the roots") no longer holds.
        String description = scope == null ? tool.description() : tool.description()
                + " In DevMate, 'path' is relative to the selected folder; omit it to use the folder itself.";
        return new LlmTool(tool.name(), description, json.writeValueAsString(tool.inputSchema()), arguments -> {
            Map<String, Object> parsed = parseObject(arguments);
            if (parsed == null) {
                return LlmTool.Output.error("INVALID_ARGUMENT: arguments must be a JSON object");
            }
            try {
                parsed = scope == null ? parsed : scope.apply(parsed);
            } catch (ToolScopeException e) {
                return LlmTool.Output.error(e.getMessage());
            }
            McpToolResult result = mcp.callTool(tool.name(), parsed);
            String text = truncate(result.text());
            return new LlmTool.Output(text, result.error(), summarize(result, text.length() < result.text().length()));
        });
    }

    /** The arguments as a map, or null when they are not a JSON object (also for the literal {@code null}). */
    private Map<String, Object> parseObject(String arguments) {
        try {
            return json.readValue(arguments, ARGUMENTS);
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * One line on a tool's outcome for the user: for a JSON object, its top-level counts and flags (arrays as their
     * size; strings such as paths and content are left out), e.g. {@code matches=3 filesScanned=40 truncated=false}.
     * Null (the {@link LlmTool.Output} default) for errors and for anything else.
     */
    private String summarize(McpToolResult result, boolean cutOff) {
        if (result.error()) {
            return null;
        }
        var stats = new StringJoiner(" ");
        try {
            json.readTree(result.text()).properties().forEach(field -> {
                JsonNode value = field.getValue();
                if (value.isArray()) {
                    stats.add(field.getKey() + "=" + value.size());
                } else if (value.isNumber() || value.isBoolean()) {
                    stats.add(field.getKey() + "=" + value);
                }
            });
        } catch (JacksonException e) {
            // not JSON: the default summary (its length) is all we can say
        }
        if (cutOff) {
            stats.add("(cut off by DevMate at " + limits.maxToolResultChars() + " chars)");
        }
        return stats.length() == 0 ? null : stats.toString();
    }

    private String truncate(String text) {
        int max = limits.maxToolResultChars();
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end) + "\n…[output cut off by DevMate after " + max + " characters]";
    }
}
