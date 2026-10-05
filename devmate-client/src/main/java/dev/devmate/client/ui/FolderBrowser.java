package dev.devmate.client.ui;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Lets the folder picker browse this machine's directories, so the user clicks a folder instead of typing its path.
 * Browsers never reveal a picked folder's absolute path to a page, and DevMate runs on the user's machine, so the
 * listing is done here.
 *
 * <p>Only <em>subdirectory names</em> are returned: no files, no contents, no hidden ({@code .name}) directories.
 * Seeing a folder's name grants nothing: reading it still goes through the files MCP server's sandbox (allowed roots or
 * the "Allow" popup).
 */
@Component
public class FolderBrowser {

    static final int MAX_FOLDERS = 500;

    /** One clickable directory: its display name and absolute path. */
    public record Folder(String name, String path) {}

    /**
     * @param path      the listed directory (absolute, symlinks resolved)
     * @param parent    its parent, or null at the filesystem root
     * @param crumbs    the path from the filesystem root down to {@code path}, for the breadcrumb
     * @param folders   visible subdirectories, sorted by name, at most {@link #MAX_FOLDERS}
     * @param truncated whether more subdirectories exist than were returned
     */
    public record Listing(String path, String parent, List<Folder> crumbs, List<Folder> folders, boolean truncated) {}

    /** A directory that can't be listed; the message is shown to the user as is. */
    public static final class BrowseException extends RuntimeException {
        BrowseException(String message) {
            super(message);
        }
    }

    private final Path home;

    public FolderBrowser(@Value("${devmate.ui.browse-start:${user.home}}") String home) {
        this.home = Path.of(home).toAbsolutePath().normalize();
    }

    /** Where the picker starts when nothing else is open. */
    public String home() {
        return home.toString();
    }

    /**
     * Lists the subdirectories of {@code rawPath} (the home directory when blank).
     *
     * @throws BrowseException if the path is relative, missing, not a directory or not readable
     */
    public Listing list(String rawPath) {
        Path directory = toRealDirectory(rawPath == null || rawPath.isBlank() ? home.toString() : rawPath.strip());
        var folders = new ArrayList<Folder>();
        boolean truncated = false;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory, Files::isDirectory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (folders.size() == MAX_FOLDERS) {
                    truncated = true;
                    break;
                }
                folders.add(new Folder(name, child.toString()));
            }
        } catch (AccessDeniedException e) {
            throw new BrowseException("Can't open " + directory + ": permission denied");
        } catch (IOException e) {
            throw new BrowseException("Can't open " + directory);
        }
        folders.sort(Comparator.comparing(Folder::name, String.CASE_INSENSITIVE_ORDER));
        Path parent = directory.getParent();
        return new Listing(directory.toString(), parent == null ? null : parent.toString(), crumbs(directory),
                List.copyOf(folders), truncated);
    }

    private static Path toRealDirectory(String raw) {
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException e) {
            throw new BrowseException("Not a valid path");
        }
        if (!path.isAbsolute()) {
            throw new BrowseException("Enter an absolute path");
        }
        try {
            Path real = path.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new BrowseException("Not a folder: " + raw);
            }
            return real;
        } catch (NoSuchFileException e) {
            throw new BrowseException("No such folder: " + raw);
        } catch (IOException e) {
            throw new BrowseException("Can't open " + raw);
        }
    }

    private static List<Folder> crumbs(Path directory) {
        var crumbs = new ArrayList<Folder>();
        Path current = directory.getRoot();
        crumbs.add(new Folder(current.toString(), current.toString()));
        if (directory.equals(current)) {
            return List.copyOf(crumbs);
        }
        for (Path segment : current.relativize(directory)) {
            current = current.resolve(segment);
            crumbs.add(new Folder(segment.toString(), current.toString()));
        }
        return List.copyOf(crumbs);
    }
}
