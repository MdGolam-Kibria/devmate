package dev.devmate.files.access;

import static dev.devmate.files.access.FileAccessException.Reason.ACCESS_DENIED;
import static dev.devmate.files.access.FileAccessException.Reason.INTERNAL_ERROR;
import static dev.devmate.files.access.FileAccessException.Reason.INVALID_ARGUMENT;
import static dev.devmate.files.access.FileAccessException.Reason.NOT_FOUND;

import dev.devmate.files.config.FilesProperties;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The single gate between tool arguments and the filesystem. Every path a tool touches must come from
 * {@link #resolve(String)} (for user input) or pass {@link #isVisible(Path)} (for paths found while walking).
 *
 * <p>Rules: a path is allowed only if its <em>real</em> path (symlinks resolved) lies inside an allowed root, and no
 * segment below the root is an excluded directory, and its file name doesn't match a denied glob.
 */
@Component
public final class FileAccessPolicy {

    private final List<Path> roots;
    private final Set<String> excludedDirectories;
    private final List<PathMatcher> deniedFiles;

    public FileAccessPolicy(FilesProperties properties) {
        this.roots = properties.allowedRoots().stream().map(FileAccessPolicy::toRealDirectory).distinct().toList();
        this.excludedDirectories = Set.copyOf(properties.excludedDirectories());
        this.deniedFiles = properties.deniedFiles().stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                .toList();
    }

    /** Real paths of the allowed roots, in configured order. Relative tool paths resolve against the first. */
    public List<Path> roots() {
        return roots;
    }

    /**
     * Turns an untrusted tool argument into a real, existing, permitted path.
     *
     * @throws FileAccessException {@code INVALID_ARGUMENT}, {@code NOT_FOUND} or {@code ACCESS_DENIED}
     */
    public Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new FileAccessException(INVALID_ARGUMENT, "path must not be blank");
        }
        Path requested;
        try {
            Path parsed = Path.of(rawPath.strip());
            requested = (parsed.isAbsolute() ? parsed : roots.getFirst().resolve(parsed)).normalize();
        } catch (InvalidPathException e) {
            throw new FileAccessException(INVALID_ARGUMENT, "not a valid path");
        }

        if (!Files.exists(requested)) {
            // Don't reveal whether paths outside the sandbox exist.
            throw rootContaining(requested).isPresent()
                    ? new FileAccessException(NOT_FOUND, "no such file or directory: " + rawPath)
                    : outsideRoots();
        }
        Path real;
        try {
            real = requested.toRealPath();
        } catch (IOException e) {
            throw new FileAccessException(INTERNAL_ERROR, "cannot resolve path: " + rawPath);
        }
        if (!isVisible(real)) {
            throw rootContaining(real).isPresent()
                    ? new FileAccessException(ACCESS_DENIED, "path is excluded by policy: " + rawPath)
                    : outsideRoots();
        }
        return real;
    }

    /** Whether a real path is inside a root and not excluded or denied. Used to filter directory walks. */
    public boolean isVisible(Path realPath) {
        return rootContaining(realPath)
                .map(root -> isPermittedBelow(root, realPath))
                .orElse(false);
    }

    private boolean isPermittedBelow(Path root, Path realPath) {
        Path relative = root.relativize(realPath);
        for (Path segment : relative) {
            if (excludedDirectories.contains(segment.toString())) {
                return false;
            }
        }
        Path fileName = realPath.getFileName();
        return fileName == null || realPath.equals(root) || deniedFiles.stream().noneMatch(m -> m.matches(fileName));
    }

    private Optional<Path> rootContaining(Path path) {
        return roots.stream().filter(path::startsWith).findFirst();
    }

    private static FileAccessException outsideRoots() {
        return new FileAccessException(ACCESS_DENIED, "path is outside the allowed roots");
    }

    private static Path toRealDirectory(Path configured) {
        try {
            Path real = configured.toAbsolutePath().toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalStateException("devmate.filesystem.allowed-roots entry is not a directory: " + configured);
            }
            return real;
        } catch (IOException e) {
            throw new IllegalStateException("devmate.filesystem.allowed-roots entry does not exist: " + configured, e);
        }
    }
}
