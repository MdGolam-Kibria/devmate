package dev.devmate.files.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One matching line. {@code line} is 1-based; {@code text} is stripped and may be truncated. {@code before} and
 * {@code after} hold the requested context lines (not stripped, may be truncated) and are left out when empty.
 */
public record SearchMatch(String path, int line, String text,
                          @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> before,
                          @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> after) {

    public SearchMatch {
        before = before == null ? List.of() : List.copyOf(before);
        after = after == null ? List.of() : List.copyOf(after);
    }

    public SearchMatch(String path, int line, String text) {
        this(path, line, text, List.of(), List.of());
    }
}
