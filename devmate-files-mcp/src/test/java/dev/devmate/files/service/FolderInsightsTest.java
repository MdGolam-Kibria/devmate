package dev.devmate.files.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.devmate.files.TestWorkspace;
import dev.devmate.files.access.FileAccessException;
import dev.devmate.files.access.FileAccessException.Reason;
import dev.devmate.files.access.FileAccessPolicy;
import dev.devmate.files.config.FilesProperties;
import dev.devmate.files.service.FolderOverviewResult.FileType;
import dev.devmate.files.service.FolderOverviewResult.ProjectFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderInsightsTest {

    @TempDir
    Path tmp;

    private TestWorkspace ws;
    private FolderInsights insights;

    @BeforeEach
    void setUp() {
        ws = TestWorkspace.create(tmp);
        insights = insightsWith(ws.properties());
    }

    // --- folder_tree ------------------------------------------------------------------------------------------

    @Test
    void treeShowsNestedLayoutAndHidesWhatTheSandboxHides() {
        var result = insights.tree(ws.root().toString(), 8);

        assertThat(result.tree()).isEqualTo("""
                src/
                  main/
                    java/
                      com/
                        acme/
                          KafkaConfig.java
                          PaymentProducer.java
                          PaymentService.java
                    resources/
                      application.yml
                big.txt
                escape.txt@
                logo.png
                README.md
                """);
        assertThat(result.directories()).isEqualTo(6);
        assertThat(result.files()).isEqualTo(8);
        assertThat(result.truncated()).isFalse();
        assertThat(result.path()).isEqualTo(ws.root().toString());
    }

    @Test
    void treeMarksDirectoriesBelowTheDepthLimit() {
        var result = insights.tree(".", 2);

        assertThat(result.tree()).startsWith("src/\n  main/…\nbig.txt\n");
        assertThat(result.depth()).isEqualTo(2);
    }

    @Test
    void treeDepthDefaultsToThreeAndIsCappedAtLimit() {
        assertThat(insights.tree(".", null).depth()).isEqualTo(3);
        assertThat(insights.tree(".", 50).depth()).isEqualTo(8);
    }

    @Test
    void treeWithoutPathStartsAtEachRoot() {
        var result = insights.tree(null, 1);

        assertThat(result.path()).isNull();
        assertThat(result.tree()).startsWith(ws.root() + "/\n  src/…\n");
    }

    @Test
    void treeTruncatesAtEntryLimit() {
        var limited = insightsWith(withLimits(ws.properties(), 20_000, 3));

        var result = limited.tree(".", 8);

        assertThat(result.directories() + result.files()).isEqualTo(3);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void treeRejectsBadArguments() {
        assertReason(() -> insights.tree(".", 0), Reason.INVALID_ARGUMENT);
        assertReason(() -> insights.tree("README.md", 1), Reason.NOT_A_DIRECTORY);
        assertReason(() -> insights.tree("../outside", 1), Reason.ACCESS_DENIED);
        assertReason(() -> insights.tree("target", 1), Reason.ACCESS_DENIED);
    }

    // --- find_files -------------------------------------------------------------------------------------------

    @Test
    void findMatchesFileNamesAndSkipsWhatTheSandboxHides() {
        var result = insights.findFiles("*.java", null);

        assertThat(result.files()).extracting(FileEntry::name)
                .containsExactly("KafkaConfig.java", "PaymentProducer.java", "PaymentService.java");
        assertThat(result.files()).allSatisfy(f -> {
            assertThat(f.size()).isPositive();
            assertThat(f.modified()).isNotBlank();
        });
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void findNeverReturnsSecretsOrSymlinks() {
        assertThat(insights.findFiles(".env*", null).files()).isEmpty();
        assertThat(insights.findFiles("escape.txt", null).files()).isEmpty();
        assertThat(insights.findFiles("Generated.java", null).files()).isEmpty();
    }

    @Test
    void findWithSlashMatchesRelativePath() {
        assertThat(insights.findFiles("src/main/resources/*.yml", ".").files())
                .extracting(FileEntry::name).containsExactly("application.yml");
        assertThat(insights.findFiles("*/*.yml", ".").files()).isEmpty();
    }

    @Test
    void findTruncatesAtResultLimit() {
        var limited = insightsWith(withLimits(ws.properties(), 20_000, 2));

        var result = limited.findFiles("*.java", null);

        assertThat(result.files()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void findRejectsBadArguments() {
        assertReason(() -> insights.findFiles(" ", null), Reason.INVALID_ARGUMENT);
        assertReason(() -> insights.findFiles("[unclosed", null), Reason.INVALID_ARGUMENT);
        assertReason(() -> insights.findFiles("*", "../outside"), Reason.ACCESS_DENIED);
    }

    // --- folder_overview --------------------------------------------------------------------------------------

    @Test
    void overviewSummarisesVisibleFilesOnly() throws Exception {
        Files.writeString(ws.root().resolve("pom.xml"), "<project/>");
        Files.createDirectories(ws.root().resolve(".github/workflows"));
        Files.writeString(ws.root().resolve(".github/workflows/ci.yml"), "on: push");

        var result = insights.overview(".");

        assertThat(result.path()).isEqualTo(ws.root().toString());
        assertThat(result.files()).isEqualTo(9);    // no .env, target/, or symlink
        assertThat(result.directories()).isEqualTo(8);
        assertThat(result.truncated()).isFalse();
        assertThat(result.fileTypes()).first().isEqualTo(new FileType("java", 3,
                result.fileTypes().getFirst().bytes()));
        assertThat(result.fileTypes()).extracting(FileType::extension).contains("md", "yml", "png", "txt", "xml");
        assertThat(result.projectFiles()).extracting(ProjectFile::kind)
                .containsExactly("README", "Maven build", "GitHub Actions workflow", "Spring config");
        assertThat(result.largestFiles()).first().extracting(FileEntry::name).isEqualTo("big.txt");
        assertThat(result.totalBytes()).isEqualTo(result.fileTypes().stream().mapToLong(FileType::bytes).sum());
    }

    @Test
    void overviewListsMostRecentlyModifiedFirst() throws Exception {
        Files.setLastModifiedTime(ws.root().resolve("README.md"),
                FileTime.from(Instant.parse("2099-01-01T00:00:00Z")));

        assertThat(insights.overview(".").recentlyModified()).first()
                .extracting(FileEntry::name).isEqualTo("README.md");
    }

    @Test
    void overviewCapsTopListsAndMarksScanLimit() throws Exception {
        for (int i = 0; i < 15; i++) {
            Files.writeString(ws.root().resolve("f" + i + ".log"), "x".repeat(i));
        }
        assertThat(insights.overview(".").largestFiles()).hasSize(10);

        var limited = insightsWith(withLimits(ws.properties(), 2, 2000));
        var result = limited.overview(".");
        assertThat(result.files()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void overviewRejectsFilesAndPathsOutsideTheSandbox() {
        assertReason(() -> insights.overview("README.md"), Reason.NOT_A_DIRECTORY);
        assertReason(() -> insights.overview("/etc"), Reason.ACCESS_DENIED);
    }

    @Test
    void overviewDeduplicatesOverlappingRoots() {
        var p = ws.properties();
        var overlapping = insightsWith(new FilesProperties(List.of(ws.root(), ws.root().resolve("src")),
                p.maxFileSize(), p.maxListEntries(), p.maxSearchResults(), p.maxSearchFiles(), p.maxLineLength(),
                p.maxRangeFileSize(), p.maxContextLines(), p.maxTreeDepth(), p.maxTreeEntries(),
                p.excludedDirectories(), p.deniedFiles(), false));

        assertThat(overlapping.overview(null).files()).isEqualTo(insights.overview(null).files());
    }

    private static FilesProperties withLimits(FilesProperties p, int maxSearchFiles, int maxTreeEntries) {
        return new FilesProperties(p.allowedRoots(), p.maxFileSize(), p.maxListEntries(), p.maxSearchResults(),
                maxSearchFiles, p.maxLineLength(), p.maxRangeFileSize(), p.maxContextLines(), p.maxTreeDepth(),
                maxTreeEntries, p.excludedDirectories(), p.deniedFiles(), false);
    }

    private FolderInsights insightsWith(FilesProperties properties) {
        return new FolderInsights(new FileAccessPolicy(properties), properties);
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FileAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
