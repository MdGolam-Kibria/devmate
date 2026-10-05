package dev.devmate.files.service;

/** One directory entry returned by {@code list_files}. {@code size} is in bytes; 0 for directories. */
public record FileEntry(String name, String path, Type type, long size) {

    public enum Type {
        FILE,
        DIRECTORY,
        OTHER
    }
}
