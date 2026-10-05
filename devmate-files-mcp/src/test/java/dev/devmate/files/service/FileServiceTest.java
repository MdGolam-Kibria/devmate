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
    void listTruncatesAtLimit() {
        var p = ws.properties();
        var limited = serviceWith(new FilesProperties(p.allowedRoots(), p.maxFileSize(), 2, p.maxSearchResults(),
                p.maxSearchFiles(), p.maxLineLength(), p.excludedDirectories(), p.deniedFiles()));

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
                p.maxSearchFiles(), p.maxLineLength(), p.excludedDirectories(), p.deniedFiles()));

        var result = limited.searchCode("KafkaTemplate", null, null);

        assertThat(result.matches()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void searchTruncatesAtFileScanLimit() {
        var p = ws.properties();
        var limited = serviceWith(new FilesProperties(p.allowedRoots(), p.maxFileSize(), p.maxListEntries(),
                p.maxSearchResults(), 1, p.maxLineLength(), p.excludedDirectories(), p.deniedFiles()));

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
                p.excludedDirectories(), p.deniedFiles()));

        assertThat(overlapping.searchCode("KafkaTemplate", null, null).matches()).hasSize(3);
    }

    @Test
    void searchRejectsBadArguments() {
        assertReason(() -> service.searchCode(" ", null, null), Reason.INVALID_ARGUMENT);
        assertReason(() -> service.searchCode("x".repeat(501), null, null), Reason.INVALID_ARGUMENT);
        assertReason(() -> service.searchCode("x", null, "[unclosed"), Reason.INVALID_ARGUMENT);
        assertReason(() -> service.searchCode("x", "../outside", null), Reason.ACCESS_DENIED);
    }

    private FileService serviceWith(FilesProperties properties) {
        return new FileService(new FileAccessPolicy(properties), properties);
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FileAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
