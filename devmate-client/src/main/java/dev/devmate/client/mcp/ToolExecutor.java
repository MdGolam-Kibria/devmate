package dev.devmate.client.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Runs one {@code tools/call}: look the tool up in the {@link ToolRegistry}, apply DevMate's policy (read-only tools
 * only), check the arguments against the tool's input schema (so bad calls never reach the server), call it, and map
 * every outcome to an {@link McpToolResult} or, when the server can't be used, an {@link McpUnavailableException}.
 *
 * <p>Logs and times each call (MDC {@code mcpServer}/{@code toolName}; timer {@code devmate.mcp.client.calls} tagged
 * server, tool and outcome). Arguments and results are never logged.
 */
public final class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    static final String METRIC = "devmate.mcp.client.calls";
    private static final String UNKNOWN = "<unknown>";

    private final ToolRegistry registry;
    private final JsonSchemaValidator validator;
    private final MeterRegistry meters;

    public ToolExecutor(ToolRegistry registry, JsonSchemaValidator validator, MeterRegistry meters) {
        this.registry = registry;
        this.validator = validator;
        this.meters = meters;
    }

    /**
     * Calls a tool by name. Read-only tools are re-sent once on a new session after a broken connection.
     *
     * @param arguments the tool's arguments; null means none
     * @throws McpUnavailableException if the server that offers it (or, for an unknown name, any server) can't be used
     */
    public McpToolResult execute(String name, Map<String, Object> arguments) {
        long start = System.nanoTime();
        String server = UNKNOWN;
        String tool = UNKNOWN;
        String outcome = "UNEXPECTED";
        try (var ignoredTool = MDC.putCloseable("toolName", McpConnection.clean(name))) {
            Optional<ToolRegistry.Entry> entry = registry.find(name);
            if (entry.isEmpty()) {
                outcome = McpErrorCode.UNKNOWN_TOOL.name();
                return McpToolResult.failure(McpErrorCode.UNKNOWN_TOOL,
                        "no connected MCP server offers a tool named '" + McpConnection.clean(name) + "'");
            }
            server = entry.get().connection().name();
            tool = name;
            try (var ignoredServer = MDC.putCloseable("mcpServer", server)) {
                McpToolResult result = call(entry.get(), arguments == null ? Map.of() : arguments);
                outcome = result.error() ? result.errorCode().name() : "SUCCESS";
                return result;
            }
        } catch (McpUnavailableException e) {
            server = e.server();
            outcome = e.code().name();
            throw e;
        } finally {
            long nanos = System.nanoTime() - start;
            Timer.builder(METRIC).description("MCP tools/call made by DevMate")
                    .tag("server", server).tag("tool", tool).tag("outcome", outcome)
                    .register(meters).record(nanos, TimeUnit.NANOSECONDS);
            log.info("MCP tools/call server={} tool={} outcome={} durationMs={}", server, McpConnection.clean(name),
                    outcome, nanos / 1_000_000);
        }
    }

    private McpToolResult call(ToolRegistry.Entry entry, Map<String, Object> arguments) {
        McpTool tool = entry.tool();
        if (!tool.readOnly()) {
            return McpToolResult.failure(McpErrorCode.TOOL_NOT_ALLOWED, "'" + tool.name() + "' is not declared "
                    + "read-only by its server; DevMate only runs read-only tools (human approval arrives in M13)");
        }
        String invalid = validate(tool, arguments);
        if (invalid != null) {
            return McpToolResult.failure(McpErrorCode.INVALID_ARGUMENTS, invalid);
        }
        try {
            return entry.connection().call("tools/call", session -> session.callTool(tool.name(), arguments), true);
        } catch (McpError e) {
            return answer(entry, e);
        } catch (McpUnavailableException e) {
            if (e.code() == McpErrorCode.TIMEOUT) {   // the server is alive; this call was too slow
                return McpToolResult.failure(McpErrorCode.TIMEOUT, e.getMessage());
            }
            throw e;
        }
    }

    /** A JSON-RPC error answer to tools/call. */
    private McpToolResult answer(ToolRegistry.Entry entry, McpError e) {
        int code = e.getJsonRpcError() == null ? McpSchema.ErrorCodes.INTERNAL_ERROR : e.getJsonRpcError().code();
        String message = McpConnection.clean(e.getMessage());
        if (code == McpSchema.ErrorCodes.INVALID_PARAMS && message.startsWith("Unknown tool")) {
            registry.invalidate(entry.connection().name());   // our list is out of date
            return McpToolResult.failure(McpErrorCode.UNKNOWN_TOOL, "server '" + entry.connection().name()
                    + "' no longer offers '" + entry.tool().name() + "'");
        }
        if (code == McpSchema.ErrorCodes.INVALID_PARAMS) {
            return McpToolResult.failure(McpErrorCode.INVALID_ARGUMENTS, message);
        }
        return McpToolResult.failure(McpErrorCode.SERVER_ERROR, message + " (JSON-RPC " + code + ")");
    }

    /** Null if the arguments fit the tool's input schema (or the schema can't be used for checking). */
    private String validate(McpTool tool, Map<String, Object> arguments) {
        if (tool.inputSchema().isEmpty()) {
            return null;
        }
        try {
            var response = validator.validate(tool.inputSchema(), arguments);
            return response.valid() ? null : McpConnection.clean(response.errorMessage());
        } catch (RuntimeException e) {
            // A schema the validator can't handle is the server's problem; let the server judge the arguments.
            log.debug("MCP input schema not checked tool={} error={}", tool.name(), e.getClass().getSimpleName());
            return null;
        }
    }
}
