package dev.devmate.client.folder;

/**
 * A folder can't be opened or used: blank or relative path, outside the files MCP sandbox, missing, not a directory.
 * The message is {@code REASON: detail} (the files MCP convention) and is safe to show to users.
 */
public final class FolderException extends RuntimeException {

    public FolderException(String message) {
        super(message);
    }

    /** The {@code REASON} part, e.g. {@code ACCESS_DENIED}. */
    public String reason() {
        String message = getMessage();
        int colon = message.indexOf(':');
        return colon > 0 ? message.substring(0, colon) : "INVALID_ARGUMENT";
    }
}
