package dev.devmate.files.service;

import static dev.devmate.files.access.FileAccessException.Reason.BINARY_FILE;
import static dev.devmate.files.access.FileAccessException.Reason.INTERNAL_ERROR;
import static dev.devmate.files.access.FileAccessException.Reason.INVALID_ARGUMENT;
import static dev.devmate.files.access.FileAccessException.Reason.NOT_A_DIRECTORY;
import static dev.devmate.files.access.FileAccessException.Reason.NOT_A_FILE;
import static dev.devmate.files.access.FileAccessException.Reason.TOO_LARGE;

import dev.devmate.files.access.FileAccessException;
import dev.devmate.files.access.FileAccessPolicy;
import dev.devmate.files.config.FilesProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Service;

/**
 * Read-only file operations behind the MCP tools. Knows nothing about MCP; every path goes through
 * {@link FileAccessPolicy}. Symbolic links are never followed while walking, and only resolved (then re-checked)
 * when a caller names one explicitly.
 */
@Service
public class FileService {

    /** Bytes probed for NUL to classify a file as binary (the heuristic git uses). */
    private static final int BINARY_PROBE_BYTES = 8 * 1024;
    private static final int MAX_QUERY_LENGTH = 500;
    private static final Comparator<FileEntry> DIRECTORIES_FIRST = Comparator
            .comparing((FileEntry e) -> e.type() != FileEntry.Type.DIRECTORY)
            .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER);

    private final FileAccessPolicy policy;
    private final FilesProperties properties;

    public FileService(FileAccessPolicy policy, FilesProperties properties) {
        this.policy = policy;
        this.properties = properties;
    }

    /** Lists a directory, or the allowed roots when {@code path} is blank. */
    public ListFilesResult listFiles(String path) {
        if (path == null || path.isBlank()) {
            var rootEntries = policy.roots().stream()
                    .map(root -> new FileEntry(root.toString(), root.toString(), FileEntry.Type.DIRECTORY, 0))
                    .toList();
            return new ListFilesResult(null, rootEntries, false);
        }
        Path directory = policy.resolve(path);
        if (!Files.isDirectory(directory)) {
            throw new FileAccessException(NOT_A_DIRECTORY, path);
        }

        var entries = new ArrayList<FileEntry>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (!policy.isVisible(child)) {
                    continue;
                }
                if (entries.size() == properties.maxListEntries()) {
                    truncated = true;
                    break;
                }
                entries.add(toEntry(child));
            }
        } catch (IOException e) {
            throw new FileAccessException(INTERNAL_ERROR, "cannot list directory: " + path);
        }
        entries.sort(DIRECTORIES_FIRST);
        return new ListFilesResult(directory.toString(), entries, truncated);
    }

    /** Returns a whole text file, subject to the size limit. */
    public ReadFileResult readFile(String path) {
        Path file = policy.resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new FileAccessException(NOT_A_FILE, path);
        }
        byte[] bytes = readBounded(file)
                .orElseThrow(() -> new FileAccessException(TOO_LARGE,
                        "file exceeds " + properties.maxFileSize().toBytes() + " bytes: " + path));
        if (isBinary(bytes)) {
            throw new FileAccessException(BINARY_FILE, path);
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        return new ReadFileResult(file.toString(), bytes.length, (int) content.lines().count(), content);
    }

    /**
     * Case-insensitive literal (not regex) search over text files.
     *
     * @param path optional file or directory to search in; all roots when blank
     * @param glob optional file-name glob such as {@code *.java}
     */
    public SearchCodeResult searchCode(String query, String path, String glob) {
        if (query == null || query.isBlank()) {
            throw new FileAccessException(INVALID_ARGUMENT, "query must not be blank");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new FileAccessException(INVALID_ARGUMENT, "query longer than " + MAX_QUERY_LENGTH + " characters");
        }
        List<Path> starts = path == null || path.isBlank() ? policy.roots() : List.of(policy.resolve(path));
        var search = new Search(query.toLowerCase(Locale.ROOT), fileNameMatcher(glob));
        for (Path start : starts) {
            if (search.stopped()) {
                break;
            }
            try {
                Files.walkFileTree(start, search);
            } catch (IOException e) {
                throw new FileAccessException(INTERNAL_ERROR, "search failed");
            }
        }
        return new SearchCodeResult(query, search.matches, search.filesScanned, search.truncated);
    }

    private static Optional<PathMatcher> fileNameMatcher(String glob) {
        if (glob == null || glob.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(FileSystems.getDefault().getPathMatcher("glob:" + glob.strip()));
        } catch (PatternSyntaxException e) {
            throw new FileAccessException(INVALID_ARGUMENT, "invalid glob: " + glob);
        }
    }

    /** Stateful visitor for one search call. Never follows symlinks (walkFileTree default). */
    private final class Search extends SimpleFileVisitor<Path> {

        private final String needle;
        private final Optional<PathMatcher> fileNameMatcher;
        private final List<SearchMatch> matches = new ArrayList<>();
        private final Set<Path> visited = new HashSet<>();
        private int filesScanned;
        private boolean truncated;

        Search(String needle, Optional<PathMatcher> fileNameMatcher) {
            this.needle = needle;
            this.fileNameMatcher = fileNameMatcher;
        }

        boolean stopped() {
            return truncated;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return policy.isVisible(dir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            boolean candidate = attrs.isRegularFile()
                    && attrs.size() <= properties.maxFileSize().toBytes()
                    && policy.isVisible(file)
                    && fileNameMatcher.map(m -> m.matches(file.getFileName())).orElse(true)
                    && visited.add(file);
            if (!candidate) {
                return FileVisitResult.CONTINUE;
            }
            if (filesScanned == properties.maxSearchFiles()) {
                truncated = true;
                return FileVisitResult.TERMINATE;
            }
            filesScanned++;
            scan(file);
            return truncated ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
            return FileVisitResult.CONTINUE; // unreadable entries are skipped, not fatal
        }

        private void scan(Path file) {
            Optional<byte[]> bytes = readBounded(file);
            if (bytes.isEmpty() || isBinary(bytes.get())) {
                return;
            }
            List<String> lines = new String(bytes.get(), StandardCharsets.UTF_8).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                    if (matches.size() == properties.maxSearchResults()) {
                        truncated = true;
                        return;
                    }
                    matches.add(new SearchMatch(file.toString(), i + 1, abbreviate(line.strip())));
                }
            }
        }
    }

    /** Reads at most {@code maxFileSize} bytes; empty if the file is larger (also when it grew after a size check). */
    private Optional<byte[]> readBounded(Path file) {
        long limit = properties.maxFileSize().toBytes();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8));
            return bytes.length > limit ? Optional.empty() : Optional.of(bytes);
        } catch (IOException e) {
            throw new FileAccessException(INTERNAL_ERROR, "cannot read file: " + file.getFileName());
        }
    }

    private static boolean isBinary(byte[] bytes) {
        int probe = Math.min(bytes.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private String abbreviate(String line) {
        int max = properties.maxLineLength();
        return line.length() <= max ? line : line.substring(0, max) + "…";
    }

    private static FileEntry toEntry(Path child) {
        try {
            var attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var type = attrs.isDirectory() ? FileEntry.Type.DIRECTORY
                    : attrs.isRegularFile() ? FileEntry.Type.FILE
                    : FileEntry.Type.OTHER;
            return new FileEntry(child.getFileName().toString(), child.toString(), type,
                    type == FileEntry.Type.FILE ? attrs.size() : 0);
        } catch (IOException e) {
            return new FileEntry(child.getFileName().toString(), child.toString(), FileEntry.Type.OTHER, 0);
        }
    }
}
