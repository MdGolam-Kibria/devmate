package dev.devmate.client.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.devmate.client.ui.FolderBrowser.BrowseException;
import dev.devmate.client.ui.FolderBrowser.Folder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderBrowserTest {

    @TempDir
    Path tmp;

    private Path home;
    private FolderBrowser browser;

    @BeforeEach
    void setUp() throws IOException {
        home = tmp.toRealPath().resolve("home");
        Files.createDirectories(home.resolve("projects/shop/src"));
        Files.createDirectories(home.resolve("Documents"));
        Files.createDirectories(home.resolve("archive"));
        Files.createDirectories(home.resolve(".ssh"));
        Files.writeString(home.resolve("notes.txt"), "secret notes");
        Files.writeString(home.resolve(".bashrc"), "export X=1");
        browser = new FolderBrowser(home.toString());
    }

    @Test
    void blankPathListsTheHomeDirectory() {
        var listing = browser.list(" ");

        assertThat(listing.path()).isEqualTo(home.toString());
        assertThat(listing.parent()).isEqualTo(home.getParent().toString());
        assertThat(browser.home()).isEqualTo(home.toString());
    }

    @Test
    void listsOnlyVisibleSubfoldersSortedByName() {
        var listing = browser.list(home.toString());

        assertThat(listing.folders()).extracting(Folder::name).containsExactly("archive", "Documents", "projects");
        assertThat(listing.folders()).extracting(Folder::path)
                .containsExactly(home.resolve("archive").toString(), home.resolve("Documents").toString(),
                        home.resolve("projects").toString());
        assertThat(listing.truncated()).isFalse();
    }

    @Test
    void neverReturnsFilesOrTheirContents() {
        var listing = browser.list(home.toString());

        assertThat(listing.toString()).doesNotContain("notes.txt", ".bashrc", "secret notes", ".ssh");
    }

    @Test
    void breadcrumbRunsFromTheFilesystemRootToTheFolder() {
        var listing = browser.list(home.resolve("projects/shop").toString());

        assertThat(listing.crumbs().getFirst()).isEqualTo(new Folder("/", "/"));
        assertThat(listing.crumbs().getLast()).isEqualTo(new Folder("shop", home.resolve("projects/shop").toString()));
        assertThat(listing.crumbs()).hasSize(home.resolve("projects/shop").getNameCount() + 1);
        assertThat(listing.folders()).extracting(Folder::name).containsExactly("src");
    }

    @Test
    void filesystemRootHasNoParentAndOneCrumb() {
        var listing = browser.list("/");

        assertThat(listing.parent()).isNull();
        assertThat(listing.crumbs()).containsExactly(new Folder("/", "/"));
    }

    @Test
    void dotSegmentsAndSymlinksAreResolved() throws IOException {
        Path link = Files.createSymbolicLink(tmp.resolve("shortcut"), home.resolve("projects"));

        assertThat(browser.list(home + "/projects/shop/..").path()).isEqualTo(home.resolve("projects").toString());
        assertThat(browser.list(link.toString()).path()).isEqualTo(home.resolve("projects").toString());
    }

    @Test
    void capsTheNumberOfFolders() throws IOException {
        Path many = Files.createDirectories(tmp.resolve("many"));
        for (int i = 0; i < FolderBrowser.MAX_FOLDERS + 5; i++) {
            Files.createDirectory(many.resolve("d" + i));
        }

        var listing = browser.list(many.toString());

        assertThat(listing.folders()).hasSize(FolderBrowser.MAX_FOLDERS);
        assertThat(listing.truncated()).isTrue();
    }

    @Test
    void rejectsRelativeMissingAndFilePaths() {
        assertThatThrownBy(() -> browser.list("projects")).isInstanceOf(BrowseException.class)
                .hasMessage("Enter an absolute path");
        assertThatThrownBy(() -> browser.list(home.resolve("missing").toString())).isInstanceOf(BrowseException.class)
                .hasMessageStartingWith("No such folder");
        assertThatThrownBy(() -> browser.list(home.resolve("notes.txt").toString())).isInstanceOf(BrowseException.class)
                .hasMessageStartingWith("Not a folder");
        assertThatThrownBy(() -> browser.list("/bad\0path")).isInstanceOf(BrowseException.class)
                .hasMessage("Not a valid path");
    }

    @Test
    void unreadableFolderIsReportedNotThrownRaw() throws IOException {
        Path locked = Files.createDirectories(tmp.resolve("locked"));
        assertThat(locked.toFile().setReadable(false)).isTrue();
        try {
            if (Files.isReadable(locked)) {
                return;  // running as root: permissions aren't enforced
            }
            assertThatThrownBy(() -> browser.list(locked.toString())).isInstanceOf(BrowseException.class)
                    .hasMessageContaining("permission denied");
        } finally {
            locked.toFile().setReadable(true);
        }
    }
}
