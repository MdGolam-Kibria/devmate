package dev.devmate.files.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.devmate.files.access.ClientRoots;
import dev.devmate.files.access.FileAccessException;
import dev.devmate.files.access.FileAccessException.Reason;
import dev.devmate.files.service.FileService;
import dev.devmate.files.service.FolderInsights;
import dev.devmate.files.service.ReadFileResult;
import dev.devmate.files.service.SearchCodeResult;
import dev.devmate.files.service.SearchMatch;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FileToolsTest {

    private final FileService service = mock(FileService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final FileTools tools = new FileTools(service, mock(FolderInsights.class), mock(ClientRoots.class), json,
            meters);

    @Test
    void successIsSingleJsonTextBlock() {
        when(service.readFile(eq("a.txt"), isNull(), isNull(), any()))
                .thenReturn(new ReadFileResult("/r/a.txt", 2, 1, 1, 1, false, "hi"));

        CallToolResult result = tools.readFile(null, "a.txt", null, null);

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).isEqualTo("{\"path\":\"/r/a.txt\",\"size\":2,\"lineCount\":1,\"startLine\":1,"
                + "\"endLine\":1,\"truncated\":false,\"content\":\"hi\"}");
    }

    @Test
    void searchMatchWithoutContextOmitsContextFields() {
        when(service.searchCode(eq("q"), isNull(), isNull(), isNull(), any())).thenReturn(new SearchCodeResult("q",
                List.of(new SearchMatch("/r/a", 3, "q here")), 1, 4, false));

        assertThat(text(tools.searchCode(null, "q", null, null, null))).isEqualTo("{\"query\":\"q\",\"matches\":"
                + "[{\"path\":\"/r/a\",\"line\":3,\"text\":\"q here\"}],\"filesMatched\":1,\"filesScanned\":4,"
                + "\"truncated\":false}");
    }

    @Test
    void policyRejectionIsToolErrorWithReasonPrefix() {
        when(service.readFile(eq("x"), isNull(), isNull(), any()))
                .thenThrow(new FileAccessException(Reason.ACCESS_DENIED, "nope"));

        CallToolResult result = tools.readFile(null, "x", null, null);

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("ACCESS_DENIED: nope");
    }

    @Test
    void unexpectedFailureDoesNotLeakItsMessage() {
        when(service.searchCode(eq("q"), isNull(), isNull(), isNull(), any()))
                .thenThrow(new IllegalStateException("/home/secret/internal detail"));

        CallToolResult result = tools.searchCode(null, "q", null, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("INTERNAL_ERROR: unexpected server error");
    }

    @Test
    void statsKeepCountsAndFlagsButNoStrings() {
        var search = new SearchCodeResult("secret query", List.of(new SearchMatch("/r/a", 3, "secret text")), 1, 9,
                true);
        var read = new ReadFileResult("/r/a", 5, 2, 1, 2, false, "secret");

        assertThat(FileTools.stats(json.valueToTree(search)))
                .isEqualTo("matches=1 filesMatched=1 filesScanned=9 truncated=true");
        assertThat(FileTools.stats(json.valueToTree(read)))
                .isEqualTo("size=5 lineCount=2 startLine=1 endLine=2 truncated=false");
    }

    @Test
    void everyCallIsTimedByToolAndOutcome() {
        when(service.readFile(eq("ok"), isNull(), isNull(), any()))
                .thenReturn(new ReadFileResult("/r/ok", 0, 0, 1, 0, false, ""));
        when(service.readFile(eq("no"), isNull(), isNull(), any()))
                .thenThrow(new FileAccessException(Reason.ACCESS_DENIED, "nope"));

        tools.readFile(null, "ok", null, null);
        tools.readFile(null, "no", null, null);
        tools.readFile(null, "no", null, null);

        assertThat(meters.get(FileTools.METRIC).tags("tool", "read_file", "outcome", "success").timer().count())
                .isEqualTo(1);
        assertThat(meters.get(FileTools.METRIC)
                .tags("tool", "read_file", "outcome", "rejected", "reason", "ACCESS_DENIED").timer().count())
                .isEqualTo(2);
    }

    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().getFirst()).text();
    }
}
