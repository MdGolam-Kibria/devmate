package dev.devmate.client.folder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FolderScopeTest {

    private final FolderScope scope = new FolderScope("/work/project");

    @Test
    void missingOrBlankPathBecomesTheFolder() {
        assertThat(scope.apply(Map.of("query", "Kafka"))).containsEntry("path", "/work/project")
                .containsEntry("query", "Kafka");
        assertThat(scope.apply(Map.of("path", "  "))).containsEntry("path", "/work/project");
        var nullPath = new HashMap<String, Object>();
        nullPath.put("path", null);
        assertThat(scope.apply(nullPath)).containsEntry("path", "/work/project");
    }

    @Test
    void relativePathsResolveAgainstTheFolder() {
        assertThat(scope.apply(Map.of("path", "src/Main.java"))).containsEntry("path", "/work/project/src/Main.java");
        assertThat(scope.apply(Map.of("path", "./src/../pom.xml"))).containsEntry("path", "/work/project/pom.xml");
        assertThat(scope.apply(Map.of("path", "."))).containsEntry("path", "/work/project");
    }

    @Test
    void absolutePathsInsideTheFolderAreKept() {
        assertThat(scope.apply(Map.of("path", "/work/project/src"))).containsEntry("path", "/work/project/src");
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../other/secret.txt", "/etc/passwd", "/work/project-other", "src/../../x",
            "/work/project/../other"})
    void pathsLeavingTheFolderAreRefused(String path) {
        assertThatThrownBy(() -> scope.apply(Map.of("path", path)))
                .isInstanceOf(FolderException.class)
                .hasMessageStartingWith("ACCESS_DENIED: path is outside the selected folder")
                .extracting(e -> ((FolderException) e).reason()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void nonStringPathIsInvalid() {
        assertThatThrownBy(() -> scope.apply(Map.of("path", 42)))
                .isInstanceOf(FolderException.class).hasMessage("INVALID_ARGUMENT: path must be a string");
    }

    @Test
    void doesNotModifyTheCallersArguments() {
        var arguments = Map.<String, Object>of("path", "src");
        scope.apply(arguments);
        assertThat(arguments).containsEntry("path", "src");
    }
}
