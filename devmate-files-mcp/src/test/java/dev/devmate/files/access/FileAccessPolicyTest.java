package dev.devmate.files.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.devmate.files.TestWorkspace;
import dev.devmate.files.access.FileAccessException.Reason;
import dev.devmate.files.config.FilesProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

class FileAccessPolicyTest {

    @TempDir
    Path tmp;

    private TestWorkspace ws;
    private FileAccessPolicy policy;

    @BeforeEach
    void setUp() {
        ws = TestWorkspace.create(tmp);
        policy = new FileAccessPolicy(ws.properties());
    }

    @Test
    void resolvesRelativePathAgainstFirstRoot() {
        assertThat(policy.resolve("src/main/java/com/acme/PaymentService.java"))
                .isEqualTo(ws.root().resolve("src/main/java/com/acme/PaymentService.java"));
    }

    @Test
    void resolvesAbsolutePathInsideRoot() {
        Path readme = ws.root().resolve("README.md");
        assertThat(policy.resolve(readme.toString())).isEqualTo(readme);
    }

    @Test
    void normalisesDotSegmentsThatStayInside() {
        assertThat(policy.resolve("src/../README.md")).isEqualTo(ws.root().resolve("README.md"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside/secret.txt", "src/../../outside/secret.txt", "../../../../etc/passwd", "/etc/passwd"})
    void rejectsTraversalAndAbsolutePathsOutsideRoots(String path) {
        assertDenied(path, Reason.ACCESS_DENIED, "outside the allowed roots");
    }

    @Test
    void rejectsSymlinkThatEscapesRoot() {
        assertDenied("escape.txt", Reason.ACCESS_DENIED, "outside the allowed roots");
    }

    @Test
    void rejectsSymlinkedDirectoryThatEscapesRoot() throws Exception {
        Files.createSymbolicLink(ws.root().resolve("linked-dir"), ws.outside());
        assertDenied("linked-dir/secret.txt", Reason.ACCESS_DENIED, "outside the allowed roots");
    }

    @Test
    void doesNotRevealWhetherOutsidePathsExist() {
        assertDenied("/no/such/place/anywhere", Reason.ACCESS_DENIED, "outside the allowed roots");
    }

    @Test
    void reportsMissingFileInsideRoot() {
        assertDenied("src/Missing.java", Reason.NOT_FOUND, "src/Missing.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {".env", "target/Generated.java", "target"})
    void rejectsDeniedFilesAndExcludedDirectories(String path) {
        assertDenied(path, Reason.ACCESS_DENIED, "excluded by policy");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "bad\u0000path"})
    void rejectsBlankAndInvalidPaths(String path) {
        assertThatThrownBy(() -> policy.resolve(path))
                .isInstanceOfSatisfying(FileAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_ARGUMENT));
    }

    @Test
    void rootItselfIsVisible() {
        assertThat(policy.resolve(ws.root().toString())).isEqualTo(ws.root());
    }

    @Test
    void failsFastWhenRootDoesNotExist() {
        var props = new FilesProperties(List.of(tmp.resolve("missing")), DataSize.ofKilobytes(1), 1, 1, 1, 1,
                List.of(), List.of());
        assertThatThrownBy(() -> new FileAccessPolicy(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void failsFastWhenRootIsAFile() {
        var props = new FilesProperties(List.of(ws.root().resolve("README.md")), DataSize.ofKilobytes(1), 1, 1, 1,
                1, List.of(), List.of());
        assertThatThrownBy(() -> new FileAccessPolicy(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a directory");
    }

    private void assertDenied(String path, Reason reason, String messagePart) {
        assertThatThrownBy(() -> policy.resolve(path))
                .isInstanceOfSatisfying(FileAccessException.class, e -> {
                    assertThat(e.reason()).isEqualTo(reason);
                    assertThat(e.getMessage()).contains(messagePart).doesNotContain(ws.outside().toString());
                });
    }
}
