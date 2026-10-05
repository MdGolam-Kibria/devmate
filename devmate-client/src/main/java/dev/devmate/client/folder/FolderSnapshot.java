package dev.devmate.client.folder;

import java.util.List;

/**
 * A folder as the files MCP server sees it.
 *
 * @param path      the real path the server resolved (symlinks followed), used from then on
 * @param entries   top-level names, directories with a trailing {@code /}
 * @param truncated the server returned only part of the listing
 */
public record FolderSnapshot(String path, List<String> entries, boolean truncated) {

    public FolderSnapshot {
        entries = List.copyOf(entries);
    }
}
