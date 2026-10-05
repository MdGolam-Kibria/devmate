package dev.devmate.files.access;

/**
 * A request the files server refuses or cannot satisfy. The message is returned to the MCP client verbatim
 * (prefixed with {@link Reason}), so it must never contain file content or paths outside the allowed roots.
 */
public final class FileAccessException extends RuntimeException {

    public enum Reason {
        INVALID_ARGUMENT,
        NOT_FOUND,
        ACCESS_DENIED,
        NOT_A_FILE,
        NOT_A_DIRECTORY,
        TOO_LARGE,
        BINARY_FILE,
        INTERNAL_ERROR
    }

    private final Reason reason;

    public FileAccessException(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
