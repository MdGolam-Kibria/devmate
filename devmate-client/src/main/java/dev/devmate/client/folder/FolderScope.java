package dev.devmate.client.folder;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pins a tool's {@code path} argument to the selected folder before the call goes to the MCP server: a missing path
 * becomes the folder, a relative path is resolved against it, and anything that leaves it is refused.
 *
 * <p>This check is lexical. The real security boundary stays on the server (real paths, symlinks, deny lists); this
 * only keeps the model inside the folder the user chose, even when the server's sandbox is wider.
 */
final class FolderScope {

    static final String PATH = "path";

    private final Path folder;

    FolderScope(String folder) {
        this.folder = Path.of(folder).toAbsolutePath().normalize();
    }

    /**
     * @return a copy of {@code arguments} whose {@code path} is absolute and inside the folder
     * @throws FolderException {@code INVALID_ARGUMENT} or {@code ACCESS_DENIED}
     */
    Map<String, Object> apply(Map<String, Object> arguments) {
        var scoped = new LinkedHashMap<>(arguments);
        Object raw = scoped.get(PATH);
        if (raw != null && !(raw instanceof String)) {
            throw new FolderException("INVALID_ARGUMENT: path must be a string");
        }
        String requested = raw == null ? "" : ((String) raw).strip();
        scoped.put(PATH, requested.isEmpty() ? folder.toString() : resolve(requested).toString());
        return scoped;
    }

    private Path resolve(String requested) {
        Path resolved;
        try {
            Path parsed = Path.of(requested);
            resolved = (parsed.isAbsolute() ? parsed : folder.resolve(parsed)).normalize();
        } catch (InvalidPathException e) {
            throw new FolderException("INVALID_ARGUMENT: not a valid path");
        }
        if (!resolved.startsWith(folder)) {
            throw new FolderException("ACCESS_DENIED: path is outside the selected folder " + folder);
        }
        return resolved;
    }
}
