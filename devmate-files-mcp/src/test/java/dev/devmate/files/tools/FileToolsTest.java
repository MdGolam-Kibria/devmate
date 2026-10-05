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
import dev.devmate.files.service.ReadFileResult;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FileToolsTest {

    private final FileService service = mock(FileService.class);
    private final FileTools tools = new FileTools(service, mock(ClientRoots.class), JsonMapper.builder().build());

    @Test
    void successIsSingleJsonTextBlock() {
        when(service.readFile(eq("a.txt"), any())).thenReturn(new ReadFileResult("/r/a.txt", 2, 1, "hi"));

        CallToolResult result = tools.readFile(null, "a.txt");

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).isEqualTo("{\"path\":\"/r/a.txt\",\"size\":2,\"lineCount\":1,\"content\":\"hi\"}");
    }

    @Test
    void policyRejectionIsToolErrorWithReasonPrefix() {
        when(service.readFile(eq("x"), any())).thenThrow(new FileAccessException(Reason.ACCESS_DENIED, "nope"));

        CallToolResult result = tools.readFile(null, "x");

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("ACCESS_DENIED: nope");
    }

    @Test
    void unexpectedFailureDoesNotLeakItsMessage() {
        when(service.searchCode(eq("q"), isNull(), isNull(), any())).thenThrow(new IllegalStateException("/home/secret/internal detail"));

        CallToolResult result = tools.searchCode(null, "q", null, null);

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("INTERNAL_ERROR: unexpected server error");
    }

    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().getFirst()).text();
    }
}
