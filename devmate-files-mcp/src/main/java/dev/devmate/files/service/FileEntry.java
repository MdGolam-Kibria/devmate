package dev.devmate.files.service;

/**
 * One directory entry returned by {@code list_files}. {@code size} is in bytes; 0 for directories. {@code modified} is
 * the last-modified time (ISO-8601, UTC, seconds), null if it couldn't be read.
 */
public record FileEntry(String name, String path, Type type, long size, String modified) {

    public enum Type {
        FILE,
        DIRECTORY,
        OTHER
    }
}
