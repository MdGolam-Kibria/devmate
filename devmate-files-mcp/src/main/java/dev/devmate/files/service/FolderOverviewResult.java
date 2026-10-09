package dev.devmate.files.service;

import java.util.List;

/**
 * What a folder contains, at a glance: totals, the most common file types, the files that tell what kind of project
 * it is (build, container, CI, docs, config), the largest and the most recently changed files.
 *
 * @param path      the folder; null when the allowed roots were summarised
 * @param truncated the file-scan limit stopped the walk early, so totals are lower bounds
 */
public record FolderOverviewResult(String path, int files, int directories, long totalBytes, boolean truncated,
                                   List<FileType> fileTypes, List<ProjectFile> projectFiles, List<FileEntry> largestFiles,
                                   List<FileEntry> recentlyModified) {

    /** Files with one extension ({@code "(none)"} for files without one). */
    public record FileType(String extension, int files, long bytes) {}

    /** A file that tells what the project is, e.g. {@code pom.xml} → {@code Maven build}. */
    public record ProjectFile(String path, String kind) {}

    public FolderOverviewResult {
        fileTypes = List.copyOf(fileTypes);
        projectFiles = List.copyOf(projectFiles);
        largestFiles = List.copyOf(largestFiles);
        recentlyModified = List.copyOf(recentlyModified);
    }
}
