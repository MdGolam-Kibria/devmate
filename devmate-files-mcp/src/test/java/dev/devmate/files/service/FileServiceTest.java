package dev.devmate.files.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.devmate.files.TestWorkspace;
import dev.devmate.files.access.FileAccessException;
import dev.devmate.files.access.FileAccessException.Reason;
import dev.devmate.files.access.FileAccessPolicy;
import dev.devmate.files.config.FilesProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileServiceTest {

    @TempDir
    Path tmp;

    private TestWorkspace ws;
    private FileService service;

    @BeforeEach
    void setUp() {
        ws = TestWorkspace.create(tmp);
        service = serviceWith(ws.properties());
    }

    // --- list_files -------------------------------------------------------------------------------------------

    @Test
    void listWithoutPathReturnsRoots() {
        var result = service.listFiles(null);

        assertThat(result.path()).isNull();
        assertThat(result.entries()).extracting(FileEntry::path).containsExactly(ws.root().toString());
    }

    @Test
    void listHidesDeniedAndExcludedEntriesAndPutsDirectoriesFirst() {
        var result = service.listFiles(".");

        assertThat(result.entries()).extracting(FileEntry::name)
                .containsExactly("src", "big.txt", "escape.txt", "logo.png", "README.md");
        assertThat(result.entries()).filteredOn(e -> e.name().equals("README.md"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.type()).isEqualTo(FileEntry.Type.FILE);
                    assertThat(e.size()).isEqualTo(16);
                });
        assertThat(result.entries()).filteredOn(e -> e.name().equals("escape.txt"))
                .singleElement().extracting(FileEntry::type).isEqualTo(FileEntry.Type.OTHER);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void listReportsLastModifiedTime() throws Exception {
        Files.setLastModifiedTime(ws.root().resolve("README.md"),
                java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-01-02T03:04:05.678Z")));

        assertThat(service.listFiles(".").entries()).filteredOn(e -> e.name().equals("README.md"))
                .singleElement().extracting(FileEntry::modified).isEqualTo("2026-01-02T03:04:05Z");
    }

    @Test
    void listTruncatesAtLimit() {
        var p = ws.properties();
        var limited = serviceWith(new FilesProperties(p.allowedRoots(), p.maxFileSize(), 2, p.maxSearchResults(),
                p.maxSearchFiles(), p.maxLineLength(),
                p.maxRangeFileSize(), p.maxContextLines(), p.maxTreeDepth(), p.maxTreeEntries(),
                p.excludedDirectories(), p.deniedFiles(), false));

        var result = limited.listFiles(".");

        assertThat(result.entries()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void listRejectsFile() {
        assertReason(() -> service.listFiles("README.md"), Reason.NOT_A_DIRECTORY);
    }

    // --- read_file --------------------------------------------------------------------------------------------

    @Test
    void readReturnsContent() {
        var result = service.readFile("src/main/java/com/acme/PaymentProducer.java");

        assertThat(result.content()).contains("KafkaTemplate<String, String> template");
        assertThat(result.lineCount()).isEqualTo(5);
        assertThat(result.path()).isEqualTo(ws.root().resolve("src/main/java/com/acme/PaymentProducer.java").toString());
    }

    @Test
    void readReplacesMalformedUtf8() throws Exception {
        Files.write(ws.root().resolve("latin1.txt"), new byte[] {'c', 'a', 'f', (byte) 0xE9});

        assertThat(service.readFile("latin1.txt").content()).isEqualTo("caf�");
    }

    @Test
    void readRejectsBinaryOversizedDirectoryAndSecret() {
        assertReason(() -> service.readFile("logo.png"), Reason.BINARY_FILE);
        assertReason(() -> service.readFile("big.txt"), Reason.TOO_LARGE);
        assertReason(() -> service.readFile("src"), Reason.NOT_A_FILE);
        assertReason(() -> service.readFile(".env"), Reason.ACCESS_DENIED);
        assertReason(() -> service.readFile("escape.txt"), Reason.ACCESS_DENIED);
    }

    @Test
    void readWholeFileReportsFullRange() {
        var result = service.readFile("README.md");

        assertThat(result.startLine()).isEqualTo(1);
        assertThat(result.endLine()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void readReturnsRequestedLineRange() throws Exception {
        Files.writeString(ws.root().resolve("lines.txt"), "one\ntwo\nthree\nfour\nfive\n");

        var result = service.readFile("lines.txt", 2, 4, policy());

        assertThat(result.content()).isEqualTo("two\nthree\nfour\n");
        assertThat(result.startLine()).isEqualTo(2);
        assertThat(result.endLine()).isEqualTo(4);
        assertThat(result.lineCount()).isEqualTo(5);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void readRangeDefaultsMissingBoundAndStopsAtEndOfFile() throws Exception {
        Files.writeString(ws.root().resolve("lines.txt"), "one\ntwo\nthree");

        assertThat(service.readFile("lines.txt", 2, null, policy()))
                .satisfies(r -> assertThat(r.content()).isEqualTo("two\nthree"))
                .satisfies(r -> assertThat(r.endLine()).isEqualTo(3));
        assertThat(service.readFile("lines.txt", null, 1, policy()).content()).isEqualTo("one\n");
        assertThat(service.readFile("lines.txt", 3, 99, policy()).endLine()).isEqualTo(3);
    }

    @Test
    void readRangeWorksOnFilesTooLargeToReadWhole() {
        assertReason(() -> service.readFile("big.txt"), Reason.TOO_LARGE);

        var result = service.readFile("big.txt", 1, 1, policy());

        assertThat(result.content()).isEqualTo("KafkaTemplate\n");
        assertThat(result.lineCount()).isEqualTo(2);
        assertThat(result.size()).isGreaterThan(TestWorkspace.MAX_FILE_SIZE.toBytes());
    }

    @Test
    void readRangeTruncatesOnLineBoundaryAtContentLimit() throws Exception {
        String line = "z".repeat(99) + "\n";      // 100 chars; the limit is 4 KB
        Files.writeString(ws.root().resolve("many.txt"), line.repeat(100));

        var result = service.readFile("many.txt", 1, 100, policy());

        assertThat(result.truncated()).isTrue();
        assertThat(result.endLine()).isEqualTo(40);
        assertThat(result.content()).isEqualTo(line.repeat(40));
        assertThat(result.lineCount()).isEqualTo(100);
    }

    @Test
    void readRangeReturnsStartOfSingleOverlongLine() throws Exception {
        Files.writeString(ws.root().resolve("wide.txt"), "w".repeat(10_000));

        var result = service.readFile("wide.txt", 1, 1, policy());

        assertThat(result.truncated()).isTrue();
        assertThat(result.endLine()).isEqualTo(1);
        assertThat(result.content()).hasSize((int) TestWorkspace.MAX_FILE_SIZE.toBytes());
    }

    @Test
    void readRangeRejectsBadBoundsBinaryAndFilesOverRangeLimit() throws Exception {
        Files.writeString(ws.root().resolve("huge.txt"),
                "h".repeat((int) TestWorkspace.MAX_RANGE_FILE_SIZE.toBytes() + 1));

        assertReason(() -> service.readFile("README.md", 0, 1, policy()), Reason.INVALID_ARGUMENT);
        assertReason(() -> service.readFile("README.md", 3, 2, policy()), Reason.INVALID_ARGUMENT);
        assertReason(() -> service.readFile("README.md", 5, null, policy()), Reason.INVALID_ARGUMENT);
        assertReason(() -> service.readFile("logo.png", 1, 1, policy()), Reason.BINARY_FILE);
        assertReason(() -> service.readFile("huge.txt", 1, 1, policy()), Reason.TOO_LARGE);
        assertReason(() -> service.readFile(".env", 1, 1, policy()), Reason.ACCESS_DENIED);
    }

    @Test
    void readRangeOfEmptyFileIsEmpty() throws Exception {
        Files.writeString(ws.root().resolve("empty.txt"), "");

        var result = service.readFile("empty.txt", 1, 10, policy());

        assertThat(result.content()).isEmpty();
        assertThat(result.lineCount()).isZero();
        assertThat(result.endLine()).isZero();
    }

    @Test
    void readAcceptsFileExactlyAtLimit() throws Exception {
        Files.writeString(ws.root().resolve("exact.txt"), "y".repeat((int) TestWorkspace.MAX_FILE_SIZE.toBytes()));

        assertThat(service.readFile("exact.txt").size()).isEqualTo(TestWorkspace.MAX_FILE_SIZE.toBytes());
    }

    // --- search_code ------------------------------------------------------------------------------------------

    @Test
    void searchFindsLiteralCaseInsensitivelyAndSkipsEverythingTheSandboxHides() {
        var result = service.searchCode("KafkaTemplate", null, null);

        assertThat(result.matches()).extracting(m -> Path.of(m.path()).getFileName().toString())
                .containsExactlyInAnyOrder("PaymentProducer.java", "PaymentService.java", "KafkaConfig.java");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void searchReportsLineNumberAndStrippedText() {
        var match = service.searchCode("private final KafkaTemplate", null, null).matches().getFirst();

        assertThat(match.line()).isEqualTo(4);
        assertThat(match.text()).isEqualTo("private final KafkaTemplate<String, String> template;");
    }

    @Test
    void searchReturnsContextLinesAndCountsMatchedFiles() throws Exception {
        Files.writeString(ws.root().resolve("ctx.txt"), "a\n  b\nneedle\nc\nd\n");

        var result = service.searchCode("needle", "ctx.txt", null, 1, policy());

        assertThat(result.matches()).singleElement().satisfies(m -> {
            assertThat(m.before()).containsExactly("  b");
            assertThat(m.after()).containsExactly("c");
        });
        assertThat(result.filesMatched()).isEqualTo(1);
        assertThat(service.searchCode("KafkaTemplate", null, null).filesMatched()).isEqualTo(3);
    }

    @Test
    void searchContextIsClippedAtFileEdgesAndCappedAtLimit() throws Exception {
        Files.writeString(ws.root().resolve("ctx.txt"), "needle\n1\n2\n3\n4\n5\n6\n7\n");

        var match = service.searchCode("needle", "ctx.txt", null, 99, policy()).matches().getFirst();

        assertThat(match.before()).isEmpty();
        assertThat(match.after()).containsExactly("1", "2", "3", "4", "5");
        assertReason(() -> service.searchCode("needle", null, null, -1, policy()), Reason.INVALID_ARGUMENT);
    }

    @Test
    void searchTreatsQueryAsLiteralNotRegex() throws Exception {
        Files.writeString(ws.root().resolve("regex.txt"), "a.*b\naxxb\n");

        assertThat(service.searchCode("a.*b", null, null).matches()).singleElement()
                .extracting(SearchMatch::line).isEqualTo(1);
    }

    @Test
    void searchHonoursGlobAndPathScope() {
        assertThat(service.searchCode("kafka", null, "*.yml").matches())
                .extracting(m -> Path.of(m.path()).getFileName().toString()).containsExactly("application.yml");
        assertThat(service.searchCode("KafkaTemplate", "src/main/java/com/acme/KafkaConfig.java", null).matches())
                .hasSize(1);
    }

    @Test
    void searchTruncatesAtResultLimit() {
        var p = ws.properties();
        var limited = serviceWith(new FilesProperties(p.allowedRoots(), p.maxFileSize(), p.maxListEntries(), 2,
                p.maxSearchFiles(), p.maxLineLength(),
                p.maxRangeFileSize(), p.maxContextLines(), p.maxTreeDepth(), p.maxTreeEntries(),
                p.excludedDirectories(), p.deniedFiles(), false));

        var result = limited.searchCode("KafkaTemplate", null, null);

        assertThat(result.matches()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void searchTruncatesAtFileScanLimit() {
        var p = ws.properties();
        var limited = serviceWith(new FilesProperties(p.allowedRoots(), p.maxFileSize(), p.maxListEntries(),
                p.maxSearchResults(), 1, p.maxLineLength(),
                p.maxRangeFileSize(), p.maxContextLines(), p.maxTreeDepth(), p.maxTreeEntries(),
                p.excludedDirectories(), p.deniedFiles(), false));

        var result = limited.searchCode("package", null, null);

        assertThat(result.filesScanned()).isEqualTo(1);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void searchAbbreviatesLongLines() throws Exception {
        Files.writeString(ws.root().resolve("long.txt"), "needle" + "z".repeat(1000));

        var text = service.searchCode("needle", "long.txt", null).matches().getFirst().text();

        assertThat(text).hasSize(241).endsWith("…");
    }

    @Test
    void searchDeduplicatesOverlappingRoots() {
        var p = ws.properties();
        var overlapping = serviceWith(new FilesProperties(List.of(ws.root(), ws.root().resolve("src")),
                p.maxFileSize(), p.maxListEntries(), p.maxSearchResults(), p.maxSearchFiles(), p.maxLineLength(),
                p.maxRangeFileSize(), p.maxContextLines(), p.maxTreeDepth(), p.maxTreeEntries(),
                p.excludedDirectories(), p.deniedFiles(), false));

        assertThat(overlapping.searchCode("KafkaTemplate", null, null).matches()).hasSize(3);
    }

    @Test
    void searchRejectsBadArguments() {
        assertReason(() -> service.searchCode(" ", null, null), Reason.INVALID_ARGUMENT);
        assertReason(() -> service.searchCode("x".repeat(501), null, null), Reason.INVALID_ARGUMENT);
        assertReason(() -> service.searchCode("x", null, "[unclosed"), Reason.INVALID_ARGUMENT);
        assertReason(() -> service.searchCode("x", "../outside", null), Reason.ACCESS_DENIED);
    }

    private FileAccessPolicy policy() {
        return new FileAccessPolicy(ws.properties());
    }

    private FileService serviceWith(FilesProperties properties) {
        return new FileService(new FileAccessPolicy(properties), properties);
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FileAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
