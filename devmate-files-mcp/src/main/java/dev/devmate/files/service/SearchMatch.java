package dev.devmate.files.service;

/** One matching line. {@code line} is 1-based; {@code text} is stripped and may be truncated. */
public record SearchMatch(String path, int line, String text) {}
