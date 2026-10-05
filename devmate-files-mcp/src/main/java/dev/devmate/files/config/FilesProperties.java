package dev.devmate.files.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Filesystem sandbox for the files MCP server. There is deliberately no default root: the server refuses to start
 * until {@code devmate.filesystem.allowed-roots} (env {@code DEVMATE_FILESYSTEM_ALLOWED_ROOTS}) is set.
 *
 * @param allowedRoots        directories the tools may touch; everything outside is denied
 * @param maxFileSize         largest file {@code read_file} returns and {@code search_code} scans
 * @param maxListEntries      cap on entries returned by one {@code list_files} call
 * @param maxSearchResults    cap on matching lines returned by one {@code search_code} call
 * @param maxSearchFiles      cap on files scanned by one {@code search_code} call
 * @param maxLineLength       matching lines longer than this are truncated in search results
 * @param excludedDirectories directory names that are never listed, read or searched
 * @param deniedFiles         file-name globs (secrets, keys) that are never listed, read or searched
 */
@Validated
@ConfigurationProperties("devmate.filesystem")
public record FilesProperties(
        @NotEmpty(message = "set devmate.filesystem.allowed-roots (DEVMATE_FILESYSTEM_ALLOWED_ROOTS)")
        List<Path> allowedRoots,
        @DefaultValue("1MB") DataSize maxFileSize,
        @DefaultValue("1000") @Positive int maxListEntries,
        @DefaultValue("200") @Positive int maxSearchResults,
        @DefaultValue("20000") @Positive int maxSearchFiles,
        @DefaultValue("240") @Positive int maxLineLength,
        @DefaultValue({".git", ".svn", ".hg", ".idea", ".gradle", ".m2", "target", "build", "node_modules", "dist"})
        List<String> excludedDirectories,
        @DefaultValue({".env", ".env.*", "*.pem", "*.key", "*.p12", "*.pfx", "*.jks", "*.keystore", "id_rsa*",
                "id_dsa*", "id_ecdsa*", "id_ed25519*", ".netrc", ".npmrc", ".pgpass", "*.kdbx"})
        List<String> deniedFiles) {

    public FilesProperties {
        allowedRoots = List.copyOf(allowedRoots);
        excludedDirectories = List.copyOf(excludedDirectories);
        deniedFiles = List.copyOf(deniedFiles);
    }
}
