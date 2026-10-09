package dev.devmate.files.service;

import static dev.devmate.files.access.FileAccessException.Reason.INTERNAL_ERROR;
import static dev.devmate.files.access.FileAccessException.Reason.INVALID_ARGUMENT;
import static dev.devmate.files.access.FileAccessException.Reason.NOT_A_DIRECTORY;

import dev.devmate.files.access.FileAccessException;
import dev.devmate.files.access.FileAccessPolicy;
import dev.devmate.files.config.FilesProperties;
import dev.devmate.files.service.FolderOverviewResult.FileType;
import dev.devmate.files.service.FolderOverviewResult.ProjectFile;
import java.io.IOException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Service;

/**
 * Whole-folder views behind {@code folder_tree}, {@code find_files} and {@code folder_overview}: what a folder holds,
 * not what a file says. Same rules as {@link FileService}: every path goes through {@link FileAccessPolicy}, symbolic
 * links are never followed while walking, and every walk is bounded ({@code maxSearchFiles} files scanned,
 * {@code maxTreeEntries} entries returned). File contents are never read.
 */
@Service
public class FolderInsights {

    static final int DEFAULT_TREE_DEPTH = 3;
    private static final int TOP_FILES = 10;
    private static final int MAX_FILE_TYPES = 15;
    private static final int MAX_PROJECT_FILES = 50;
    private static final String NO_EXTENSION = "(none)";
    private static final Comparator<Path> BY_NAME =
            Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);

    /** Files that tell what a project is, by lower-case file name. */
    private static final Map<String, String> PROJECT_FILES = Map.ofEntries(
            Map.entry("pom.xml", "Maven build"),
            Map.entry("build.gradle", "Gradle build"),
            Map.entry("build.gradle.kts", "Gradle build"),
            Map.entry("settings.gradle", "Gradle settings"),
            Map.entry("settings.gradle.kts", "Gradle settings"),
            Map.entry("package.json", "npm package"),
            Map.entry("go.mod", "Go module"),
            Map.entry("cargo.toml", "Rust crate"),
            Map.entry("pyproject.toml", "Python project"),
            Map.entry("requirements.txt", "Python requirements"),
            Map.entry("setup.py", "Python setup"),
            Map.entry("gemfile", "Ruby bundle"),
            Map.entry("composer.json", "PHP Composer package"),
            Map.entry("makefile", "Makefile"),
            Map.entry("dockerfile", "Docker image"),
            Map.entry("docker-compose.yml", "Docker Compose"),
            Map.entry("docker-compose.yaml", "Docker Compose"),
            Map.entry("compose.yml", "Docker Compose"),
            Map.entry("compose.yaml", "Docker Compose"),
            Map.entry("jenkinsfile", "Jenkins pipeline"),
            Map.entry(".gitlab-ci.yml", "GitLab CI"),
            Map.entry("chart.yaml", "Helm chart"),
            Map.entry("kustomization.yaml", "Kustomize"),
            Map.entry("application.yml", "Spring config"),
            Map.entry("application.yaml", "Spring config"),
            Map.entry("application.properties", "Spring config"));

    private final FileAccessPolicy policy;  // the configured roots only
    private final FilesProperties properties;

    public FolderInsights(FileAccessPolicy policy, FilesProperties properties) {
        this.policy = policy;
        this.properties = properties;
    }

    // --- folder_tree ------------------------------------------------------------------------------------------

    /** {@link #tree(String, Integer, FileAccessPolicy)} under the configured roots. */
    public FolderTreeResult tree(String path, Integer depth) {
        return tree(path, depth, policy);
    }

    /**
     * The directory tree below {@code path} (each allowed root when blank), {@code depth} levels deep.
     *
     * @param depth levels to expand; null = 3, capped at {@code maxTreeDepth}
     */
    public FolderTreeResult tree(String path, Integer depth, FileAccessPolicy policy) {
        if (depth != null && depth < 1) {
            throw new FileAccessException(INVALID_ARGUMENT, "depth must be 1 or more");
        }
        int levels = Math.min(depth == null ? DEFAULT_TREE_DEPTH : depth, properties.maxTreeDepth());
        boolean ofRoots = path == null || path.isBlank();
        List<Path> starts = starts(path, policy);
        var tree = new Tree(levels, policy);
        for (Path start : starts) {
            if (ofRoots) {
                tree.line(0, start + "/");
                tree.render(start, 1, 1);
            } else {
                tree.render(start, 1, 0);
            }
        }
        return new FolderTreeResult(ofRoots ? null : starts.getFirst().toString(), levels,
                tree.directories, tree.files, tree.truncated, tree.text.toString());
    }

    private final class Tree {

        private final int levels;
        private final FileAccessPolicy policy;
        private final StringBuilder text = new StringBuilder();
        private int directories;
        private int files;
        private boolean truncated;

        Tree(int levels, FileAccessPolicy policy) {
            this.levels = levels;
            this.policy = policy;
        }

        void line(int indent, String entry) {
            text.repeat("  ", indent).append(entry).append('\n');
        }

        void render(Path directory, int level, int indent) {
            for (Path child : children(directory)) {
                if (directories + files == properties.maxTreeEntries()) {
                    truncated = true;
                    return;
                }
                String name = child.getFileName().toString();
                if (isDirectory(child)) {
                    directories++;
                    if (level < levels) {
                        line(indent, name + "/");
                        render(child, level + 1, indent + 1);
                    } else {
                        line(indent, name + "/…");
                    }
                } else {
                    files++;
                    line(indent, Files.isSymbolicLink(child) ? name + "@" : name);
                }
                if (truncated) {
                    return;
                }
            }
        }

        /** Visible children, directories first; at most the remaining entry budget (+1 to detect the cut). */
        private List<Path> children(Path directory) {
            int budget = properties.maxTreeEntries() - directories - files + 1;
            var children = new ArrayList<Path>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    if (policy.isVisible(child)) {
                        children.add(child);
                        if (children.size() == budget) {
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                return List.of(); // unreadable directories are shown empty, not fatal
            }
            children.sort(Comparator.comparing((Path p) -> !isDirectory(p)).thenComparing(BY_NAME));
            return children;
        }
    }

    // --- find_files -------------------------------------------------------------------------------------------

    /** {@link #findFiles(String, String, FileAccessPolicy)} under the configured roots. */
    public FindFilesResult findFiles(String glob, String path) {
        return findFiles(glob, path, policy);
    }

    /**
     * Files below {@code path} (all allowed roots when blank) whose name matches {@code glob}; a glob containing
     * {@code /} is matched against the path relative to the start instead. Sorted by path.
     */
    public FindFilesResult findFiles(String glob, String path, FileAccessPolicy policy) {
        if (glob == null || glob.isBlank()) {
            throw new FileAccessException(INVALID_ARGUMENT, "glob must not be blank");
        }
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob.strip());
        } catch (PatternSyntaxException | UnsupportedOperationException e) {
            throw new FileAccessException(INVALID_ARGUMENT, "invalid glob: " + glob);
        }
        boolean byPath = glob.contains("/");
        var found = new ArrayList<FileEntry>();
        var walk = new BoundedWalk(policy) {
            @Override
            FileVisitResult onFile(Path start, Path file, BasicFileAttributes attrs) {
                if (!matcher.matches(byPath ? start.relativize(file) : file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                if (found.size() == properties.maxTreeEntries()) {
                    truncated = true;
                    return FileVisitResult.TERMINATE;
                }
                found.add(fileEntry(file, attrs));
                return FileVisitResult.CONTINUE;
            }
        };
        walk.run(starts(path, policy));
        found.sort(Comparator.comparing(FileEntry::path));
        return new FindFilesResult(glob, found, walk.filesScanned, walk.truncated);
    }

    // --- folder_overview --------------------------------------------------------------------------------------

    /** {@link #overview(String, FileAccessPolicy)} under the configured roots. */
    public FolderOverviewResult overview(String path) {
        return overview(path, policy);
    }

    /** Totals, file types, project files, largest and newest files below {@code path} (all roots when blank). */
    public FolderOverviewResult overview(String path, FileAccessPolicy policy) {
        var types = new HashMap<String, long[]>();          // extension → {files, bytes}
        var projectFiles = new ArrayList<ProjectFile>();
        var largest = new PriorityQueue<FileEntry>(Comparator.comparingLong(FileEntry::size));
        var newest = new PriorityQueue<FileEntry>(Comparator.comparing(FileEntry::modified,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        var totals = new long[2];                            // {files, bytes}
        var walk = new BoundedWalk(policy) {
            @Override
            FileVisitResult onFile(Path start, Path file, BasicFileAttributes attrs) {
                totals[0]++;
                totals[1] += attrs.size();
                long[] type = types.computeIfAbsent(extension(file), e -> new long[2]);
                type[0]++;
                type[1] += attrs.size();
                projectKind(start, file).ifPresent(kind -> projectFiles.add(new ProjectFile(file.toString(), kind)));
                FileEntry entry = fileEntry(file, attrs);
                keepTop(largest, entry);
                keepTop(newest, entry);
                return FileVisitResult.CONTINUE;
            }
        };
        List<Path> starts = starts(path, policy);
        walk.run(starts);

        List<FileType> fileTypes = types.entrySet().stream()
                .map(e -> new FileType(e.getKey(), (int) e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingInt(FileType::files).reversed().thenComparing(FileType::extension))
                .limit(MAX_FILE_TYPES)
                .toList();
        List<ProjectFile> project = projectFiles.stream()
                .sorted(Comparator.comparingInt((ProjectFile f) -> Path.of(f.path()).getNameCount())
                        .thenComparing(ProjectFile::path))
                .limit(MAX_PROJECT_FILES)
                .toList();
        return new FolderOverviewResult(path == null || path.isBlank() ? null : starts.getFirst().toString(),
                (int) totals[0], walk.directories, totals[1], walk.truncated, fileTypes, project,
                descending(largest, Comparator.comparingLong(FileEntry::size)),
                descending(newest, Comparator.comparing(FileEntry::modified,
                        Comparator.nullsFirst(Comparator.naturalOrder()))));
    }

    private static Optional<String> projectKind(Path start, Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String kind = PROJECT_FILES.get(name);
        if (kind != null) {
            return Optional.of(kind);
        }
        if (name.startsWith("readme")) {
            return Optional.of("README");
        }
        if (name.startsWith("license") || name.startsWith("licence")) {
            return Optional.of("License");
        }
        if (name.endsWith(".csproj")) {
            return Optional.of(".NET project");
        }
        if (name.endsWith(".sln")) {
            return Optional.of(".NET solution");
        }
        Path relative = start.relativize(file);
        if (relative.toString().replace('\\', '/').contains(".github/workflows/")
                && (name.endsWith(".yml") || name.endsWith(".yaml"))) {
            return Optional.of("GitHub Actions workflow");
        }
        return Optional.empty();
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? NO_EXTENSION : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Keeps the {@link #TOP_FILES} greatest entries in a min-heap. */
    private static void keepTop(PriorityQueue<FileEntry> top, FileEntry entry) {
        top.add(entry);
        if (top.size() > TOP_FILES) {
            top.poll();
        }
    }

    private static List<FileEntry> descending(PriorityQueue<FileEntry> top, Comparator<FileEntry> order) {
        return top.stream().sorted(order.reversed()).toList();
    }

    // --- shared -----------------------------------------------------------------------------------------------

    /** The directory to walk, or every allowed root when {@code path} is blank. */
    private static List<Path> starts(String path, FileAccessPolicy policy) {
        if (path == null || path.isBlank()) {
            return policy.roots();
        }
        Path directory = policy.resolve(path);
        if (!Files.isDirectory(directory)) {
            throw new FileAccessException(NOT_A_DIRECTORY, path);
        }
        return List.of(directory);
    }

    private static boolean isDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static FileEntry fileEntry(Path file, BasicFileAttributes attrs) {
        return new FileEntry(file.getFileName().toString(), file.toString(), FileEntry.Type.FILE, attrs.size(),
                FileService.isoSeconds(attrs.lastModifiedTime()));
    }

    /**
     * Walks visible regular files once each (overlapping roots are deduplicated), stopping after
     * {@code maxSearchFiles}. Never follows symlinks (walkFileTree default). Not thread-safe: one per call.
     */
    private abstract class BoundedWalk extends SimpleFileVisitor<Path> {

        private final FileAccessPolicy policy;
        private final Set<Path> visited = new HashSet<>();
        private Path start;
        int filesScanned;
        int directories;
        boolean truncated;

        BoundedWalk(FileAccessPolicy policy) {
            this.policy = policy;
        }

        abstract FileVisitResult onFile(Path start, Path file, BasicFileAttributes attrs);

        void run(List<Path> starts) {
            for (Path each : starts) {
                if (truncated) {
                    return;
                }
                start = each;
                try {
                    Files.walkFileTree(each, this);
                } catch (IOException e) {
                    throw new FileAccessException(INTERNAL_ERROR, "walk failed");
                }
            }
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (!policy.isVisible(dir) || !visited.add(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (!dir.equals(start)) {
                directories++;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!attrs.isRegularFile() || !policy.isVisible(file) || !visited.add(file)) {
                return FileVisitResult.CONTINUE;
            }
            if (filesScanned == properties.maxSearchFiles()) {
                truncated = true;
                return FileVisitResult.TERMINATE;
            }
            filesScanned++;
            return onFile(start, file, attrs);
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
            return FileVisitResult.CONTINUE; // unreadable entries are skipped, not fatal
        }
    }
}
