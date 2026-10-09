package dev.devmate.files.service;

import java.util.List;

/**
 * {@code truncated} is true when a result or scan limit stopped the search early, so absence of a match is not
 * proof of absence in the code base. {@code filesMatched} counts files with at least one returned match.
 */
public record SearchCodeResult(String query, List<SearchMatch> matches, int filesMatched, int filesScanned,
                               boolean truncated) {

    public SearchCodeResult {
        matches = List.copyOf(matches);
    }
}
