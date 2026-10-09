package dev.devmate.files.service;

/**
 * UTF-8 decoded file content; malformed byte sequences are replaced with U+FFFD.
 *
 * @param lineCount lines in the whole file
 * @param startLine first line in {@code content} (1-based)
 * @param endLine   last line in {@code content}; below {@code startLine} only for an empty file
 * @param truncated the content limit cut the requested range short; ask again from {@code endLine + 1}
 */
public record ReadFileResult(String path, long size, int lineCount, int startLine, int endLine, boolean truncated,
                             String content) {}
