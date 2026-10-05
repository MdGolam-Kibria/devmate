package dev.devmate.files.service;

import java.util.List;

/** {@code path} is null when the allowed roots themselves are listed. */
public record ListFilesResult(String path, List<FileEntry> entries, boolean truncated) {

    public ListFilesResult {
        entries = List.copyOf(entries);
    }
}
