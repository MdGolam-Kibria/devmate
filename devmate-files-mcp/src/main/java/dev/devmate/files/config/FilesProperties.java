package dev.devmate.files.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Filesystem sandbox for the files MCP server. There is deliberately no default root. With
 * {@code allow-client-roots} on (the default in {@code application.yml}) the server may start with none: every folder
 * is then granted by the MCP client (DevMate's "Allow" popup). With it off, the server refuses to start until
 * {@code devmate.filesystem.allowed-roots} (env {@code DEVMATE_FILESYSTEM_ALLOWED_ROOTS}) is set.
 *
 * @param allowedRoots        directories the tools may always touch; may be empty when {@code allowClientRoots} is on
 * @param maxFileSize         largest file {@code read_file} returns whole and {@code search_code} scans; also the
 *                            most content one line-range read returns
 * @param maxRangeFileSize    largest file {@code read_file} reads a line range from
 * @param maxListEntries      cap on entries returned by one {@code list_files} call
 * @param maxSearchResults    cap on matching lines returned by one {@code search_code} call
 * @param maxSearchFiles      cap on files scanned by one {@code search_code} call
 * @param maxLineLength       matching lines longer than this are truncated in search results
 * @param maxContextLines     most lines of context {@code search_code} returns around a match, on each side
 * @param maxTreeDepth        deepest level {@code folder_tree} expands
 * @param maxTreeEntries      cap on entries one {@code folder_tree} call returns, and on files one {@code find_files}
 *                            call returns
 * @param excludedDirectories directory names that are never listed, read or searched
 * @param deniedFiles         file-name globs (secrets, keys) that are never listed, read or searched
 * @param allowClientRoots    also allow the folders the MCP client declares as its roots (folders its user approved).
 *                            When on, any local MCP client can widen the sandbox for its own session.
 */
@Validated
@ConfigurationProperties("devmate.filesystem")
public record FilesProperties(
        List<Path> allowedRoots,
        @DefaultValue("1MB") DataSize maxFileSize,
        @DefaultValue("1000") @Positive int maxListEntries,
        @DefaultValue("200") @Positive int maxSearchResults,
        @DefaultValue("20000") @Positive int maxSearchFiles,
        @DefaultValue("240") @Positive int maxLineLength,
        @DefaultValue("64MB") DataSize maxRangeFileSize,
        @DefaultValue("5") @PositiveOrZero int maxContextLines,
        @DefaultValue("8") @Positive int maxTreeDepth,
        @DefaultValue("2000") @Positive int maxTreeEntries,
        @DefaultValue({".git", ".svn", ".hg", ".idea", ".gradle", ".m2", "target", "build", "node_modules", "dist"})
        List<String> excludedDirectories,
        @DefaultValue({".env", ".env.*", "*.pem", "*.key", "*.p12", "*.pfx", "*.jks", "*.keystore", "id_rsa*",
                "id_dsa*", "id_ecdsa*", "id_ed25519*", ".netrc", ".npmrc", ".pgpass", "*.kdbx"})
        List<String> deniedFiles,
        @DefaultValue("false") boolean allowClientRoots) {

    public FilesProperties {
        allowedRoots = allowedRoots == null ? List.of() : List.copyOf(allowedRoots);
        if (allowedRoots.isEmpty() && !allowClientRoots) {
            throw new IllegalArgumentException("set devmate.filesystem.allowed-roots (DEVMATE_FILESYSTEM_ALLOWED_ROOTS)"
                    + " or devmate.filesystem.allow-client-roots=true (DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS)");
        }
        if (maxRangeFileSize.toBytes() < maxFileSize.toBytes()) {
            throw new IllegalArgumentException("devmate.filesystem.max-range-file-size must not be below max-file-size");
        }
        excludedDirectories = List.copyOf(excludedDirectories);
        deniedFiles = List.copyOf(deniedFiles);
    }
}
