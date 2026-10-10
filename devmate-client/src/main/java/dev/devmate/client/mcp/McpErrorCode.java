package dev.devmate.client.mcp;

/**
 * Why an MCP operation failed. The first four mean the server can't be used right now and are thrown as
 * {@link McpUnavailableException}. The rest concern one tool call and are returned as a failed {@link McpToolResult}
 * (a model can react to them, e.g. by fixing its arguments). {@link #TIMEOUT} is both: thrown when {@code initialize}
 * or {@code tools/list} is too slow, returned when one {@code tools/call} is (the server is alive, the call was slow).
 */
public enum McpErrorCode {

    /** Can't connect or initialize: refused, unknown host, connect/initialize timeout, initialize rejected. */
    CONNECTION_FAILED(true, false),
    /** Connected, but the server sent no answer within {@code request-timeout}. */
    TIMEOUT(true, true),
    /** The server no longer knows the session (e.g. it restarted) and a new one could not be used either. */
    SESSION_ERROR(true, false),
    /** The HTTP exchange broke: unexpected status, unreadable or oversized message, closed stream. */
    TRANSPORT_ERROR(true, false),

    /** No connected server offers a tool with this name. */
    UNKNOWN_TOOL(false, true),
    /** The arguments don't match the tool's input schema (checked by DevMate, or rejected by the server). */
    INVALID_ARGUMENTS(false, true),
    /** DevMate refuses to run the tool: it isn't declared read-only (human approval arrives in M13). */
    TOOL_NOT_ALLOWED(false, true),
    /** The tool ran and reported a failure ({@code isError=true}); the text is the server's message. */
    TOOL_ERROR(false, true),
    /** The server answered with a JSON-RPC error other than invalid params, or with an unusable result. */
    SERVER_ERROR(false, true);

    private final boolean unavailable;
    private final boolean callResult;

    McpErrorCode(boolean unavailable, boolean callResult) {
        this.unavailable = unavailable;
        this.callResult = callResult;
    }

    /** Whether this code can mean the server can't be used ({@link McpUnavailableException}). */
    public boolean unavailable() {
        return unavailable;
    }

    /** Whether this code can describe one failed tool call ({@link McpToolResult}). */
    public boolean callResult() {
        return callResult;
    }
}
