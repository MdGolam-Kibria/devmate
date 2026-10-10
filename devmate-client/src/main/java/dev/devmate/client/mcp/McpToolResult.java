package dev.devmate.client.mcp;

/**
 * A {@code tools/call} outcome reduced to its text.
 *
 * @param text      the tool's text content; for a failure one line {@code REASON: detail} (the server's own reason for
 *                  {@link McpErrorCode#TOOL_ERROR}, else the code)
 * @param errorCode null on success
 */
public record McpToolResult(String text, McpErrorCode errorCode) {

    public McpToolResult {
        text = text == null ? "" : text;
        if (errorCode != null && !errorCode.callResult()) {
            throw new IllegalArgumentException(errorCode + " is thrown as McpUnavailableException, not returned");
        }
    }

    public static McpToolResult success(String text) {
        return new McpToolResult(text, null);
    }

    /** A failure DevMate detected itself: the text starts with the code. */
    public static McpToolResult failure(McpErrorCode code, String detail) {
        return new McpToolResult(code.name() + ": " + detail, code);
    }

    public boolean error() {
        return errorCode != null;
    }
}
