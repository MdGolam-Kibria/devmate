package dev.devmate.client.agent;

/**
 * A tool call's arguments leave the scope of the question (e.g. a path outside the open folder). The message is
 * {@code REASON: detail}, safe to show, and goes back to the model as the tool's error.
 */
public final class ToolScopeException extends RuntimeException {

    public ToolScopeException(String message) {
        super(message);
    }
}
