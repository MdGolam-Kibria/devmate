package dev.devmate.files.service;

/** UTF-8 decoded file content; malformed byte sequences are replaced with U+FFFD. */
public record ReadFileResult(String path, long size, int lineCount, String content) {}
