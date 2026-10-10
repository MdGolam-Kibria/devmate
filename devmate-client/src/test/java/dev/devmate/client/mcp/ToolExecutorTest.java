package dev.devmate.client.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpError;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ToolExecutorTest {

    private static final Map<String, Object> QUERY_SCHEMA = Map.of("type", "object",
            "properties", Map.of("query", Map.of("type", "string")), "required", List.of("query"));

    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final McpConnection connection = mock(McpConnection.class);
    private final McpSession session = mock(McpSession.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ToolExecutor executor = new ToolExecutor(registry, McpJsonDefaults.getSchemaValidator(), meters);

    @Test
    void callsTheToolOnItsServerAsARetryableRequest() {
        offer(new McpTool("files", "search_code", "", QUERY_SCHEMA, true));
        when(session.callTool("search_code", Map.of("query", "Kafka"))).thenReturn(McpToolResult.success("found"));

        assertThat(executor.execute("search_code", Map.of("query", "Kafka"))).isEqualTo(McpToolResult.success("found"));
        verify(connection).call(eq("tools/call"), any(), eq(true));
        assertThat(meters.get(ToolExecutor.METRIC).tags("server", "files", "tool", "search_code", "outcome", "SUCCESS")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void nullArgumentsAreSentAsAnEmptyObject() {
        offer(new McpTool("files", "list_files", "", Map.of("type", "object"), true));
        when(session.callTool("list_files", Map.of())).thenReturn(McpToolResult.success("[]"));

        assertThat(executor.execute("list_files", null).error()).isFalse();
    }

    @Test
    void unknownToolNameIsCleanedInTheMessageAndNotUsedAsAMetricTag() {
        when(registry.find(anyString())).thenReturn(Optional.empty());

        McpToolResult result = executor.execute("evil\n\u001b[31mname", Map.of());

        assertThat(result.text())
                .isEqualTo("UNKNOWN_TOOL: no connected MCP server offers a tool named 'evil [31mname'");
        assertThat(meters.get(ToolExecutor.METRIC).tags("tool", "<unknown>", "outcome", "UNKNOWN_TOOL").timer().count())
                .isEqualTo(1);
    }

    @Test
    void nonReadOnlyToolIsRefusedBeforeAnyRequest() {
        offer(new McpTool("files", "delete_file", "", Map.of(), false));

        assertThat(executor.execute("delete_file", Map.of()).errorCode()).isEqualTo(McpErrorCode.TOOL_NOT_ALLOWED);
        verify(connection, never()).call(anyString(), any(), anyBoolean());
    }

    @Test
    void argumentsThatBreakTheSchemaNeverReachTheServer() {
        offer(new McpTool("files", "search_code", "", QUERY_SCHEMA, true));

        McpToolResult result = executor.execute("search_code", Map.of("query", List.of("a")));

        assertThat(result.errorCode()).isEqualTo(McpErrorCode.INVALID_ARGUMENTS);
        verify(connection, never()).call(anyString(), any(), anyBoolean());
    }

    @Test
    void aSchemaTheValidatorCannotUseIsLeftToTheServer() {
        var broken = mock(JsonSchemaValidator.class);
        when(broken.validate(any(), any())).thenThrow(new IllegalStateException("bad schema"));
        var lenient = new ToolExecutor(registry, broken, meters);
        offer(new McpTool("files", "odd", "", Map.of("type", 7), true));
        when(session.callTool(eq("odd"), any())).thenReturn(McpToolResult.success("ok"));

        assertThat(lenient.execute("odd", Map.of()).text()).isEqualTo("ok");
    }

    @Test
    void jsonRpcErrorsAreMappedByCode() {
        offer(new McpTool("files", "t", "", Map.of(), true));
        when(session.callTool(eq("t"), any()))
                .thenThrow(McpError.builder(-32602).message("Unknown tool: invalid_tool_name").build())
                .thenThrow(McpError.builder(-32602).message("bad depth").build())
                .thenThrow(McpError.builder(-32603).message("boom\nstack").build());

        assertThat(executor.execute("t", Map.of()).errorCode()).isEqualTo(McpErrorCode.UNKNOWN_TOOL);
        verify(registry).invalidate("files");
        assertThat(executor.execute("t", Map.of()))
                .isEqualTo(new McpToolResult("INVALID_ARGUMENTS: bad depth", McpErrorCode.INVALID_ARGUMENTS));
        assertThat(executor.execute("t", Map.of()))
                .isEqualTo(new McpToolResult("SERVER_ERROR: boom stack (JSON-RPC -32603)", McpErrorCode.SERVER_ERROR));
    }

    @Test
    void timeoutIsReturnedButOtherOutagesAreThrown() {
        offer(new McpTool("files", "t", "", Map.of(), true));
        when(connection.call(eq("tools/call"), any(), eq(true)))
                .thenThrow(new McpUnavailableException(McpErrorCode.TIMEOUT, "files", "too slow", null))
                .thenThrow(new McpUnavailableException(McpErrorCode.TRANSPORT_ERROR, "files", "broken", null));

        assertThat(executor.execute("t", Map.of()))
                .isEqualTo(new McpToolResult("TIMEOUT: too slow", McpErrorCode.TIMEOUT));
        assertThatThrownBy(() -> executor.execute("t", Map.of())).isInstanceOf(McpUnavailableException.class)
                .hasMessage("broken");
        assertThat(meters.get(ToolExecutor.METRIC).tags("outcome", "TRANSPORT_ERROR").timer().count()).isEqualTo(1);
    }

    @Test
    void outageDuringLookupIsThrownAndTaggedWithItsServer() {
        when(registry.find("git_log"))
                .thenThrow(new McpUnavailableException(McpErrorCode.CONNECTION_FAILED, "git", "git down", null));

        assertThatThrownBy(() -> executor.execute("git_log", Map.of())).isInstanceOf(McpUnavailableException.class);
        assertThat(meters.get(ToolExecutor.METRIC).tags("server", "git", "outcome", "CONNECTION_FAILED").timer()
                .count()).isEqualTo(1);
    }

    @Test
    void serverAndToolAreInTheMdcDuringTheCallAndRemovedAfter() {
        offer(new McpTool("files", "t", "", Map.of(), true));
        var seen = new AtomicReference<String>();
        when(session.callTool(eq("t"), any())).thenAnswer(i -> {
            seen.set(MDC.get("mcpServer") + "/" + MDC.get("toolName"));
            return McpToolResult.success("");
        });

        executor.execute("t", Map.of());

        assertThat(seen.get()).isEqualTo("files/t");
        assertThat(MDC.get("mcpServer")).isNull();
        assertThat(MDC.get("toolName")).isNull();
    }

    @SuppressWarnings("unchecked")
    private void offer(McpTool tool) {
        when(connection.name()).thenReturn(tool.server());
        when(registry.find(tool.name())).thenReturn(Optional.of(new ToolRegistry.Entry(tool, connection)));
        when(connection.call(eq("tools/call"), any(), eq(true)))
                .thenAnswer(i -> ((Function<McpSession, Object>) i.getArgument(1)).apply(session));
    }
}
