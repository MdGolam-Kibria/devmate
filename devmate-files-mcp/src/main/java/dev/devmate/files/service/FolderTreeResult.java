package dev.devmate.files.service;

/**
 * A directory tree as indented text: two spaces per level, directories end in {@code /}, and a directory below the
 * depth limit ends in {@code /…} (not expanded). Hidden entries (secrets, build/VCS directories) are left out.
 *
 * @param path      the directory the tree starts at; null when the allowed roots were the start
 * @param depth     the depth that was expanded (1 = direct children only)
 * @param truncated the entry limit cut the tree short
 */
public record FolderTreeResult(String path, int depth, int directories, int files, boolean truncated, String tree) {}
