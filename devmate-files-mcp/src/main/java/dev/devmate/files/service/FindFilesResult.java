package dev.devmate.files.service;

import java.util.List;

/**
 * Files whose name (or, for a glob containing {@code /}, path below the search start) matches {@code glob}.
 * {@code truncated} is true when the result or scan limit stopped the walk early.
 */
public record FindFilesResult(String glob, List<FileEntry> files, int filesScanned, boolean truncated) {

    public FindFilesResult {
        files = List.copyOf(files);
    }
}
