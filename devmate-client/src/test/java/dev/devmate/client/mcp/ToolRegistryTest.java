package dev.devmate.client.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void listsEachServerOnceAndAgainOnlyWhenItsToolsMayHaveChanged() {
        var files = new FakeServer("files", "read_file");
        var registry = new ToolRegistry(List.of(files.connection));

        registry.tools(false);
        registry.tools(false);
        assertThat(files.listCalls()).isEqualTo(1);

        files.version.incrementAndGet();   // new session or tools/list_changed
        registry.tools(false);
        assertThat(files.listCalls()).isEqualTo(2);

        registry.tools(true);
        assertThat(files.listCalls()).isEqualTo(3);

        registry.invalidate("files");
        registry.find("read_file");
        assertThat(files.listCalls()).isEqualTo(4);
    }

    @Test
    void firstConfiguredServerWinsANameClash() {
        var files = new FakeServer("files", "search_code", "read_file");
        var git = new FakeServer("git", "search_code", "git_log");
        var registry = new ToolRegistry(List.of(files.connection, git.connection));

        assertThat(registry.tools(false).tools()).extracting(t -> t.server() + ":" + t.name())
                .containsExactly("files:search_code", "files:read_file", "git:git_log");
        assertThat(registry.find("search_code")).get().extracting(e -> e.connection().name()).isEqualTo("files");
    }

    @Test
    void findStopsAtTheFirstServerThatOffersTheTool() {
        var files = new FakeServer("files", "read_file");
        var git = new FakeServer("git", "git_log");
        var registry = new ToolRegistry(List.of(files.connection, git.connection));

        assertThat(registry.find("read_file")).isPresent();

        assertThat(git.listCalls()).isZero();
    }

    @Test
    void unreachableServersAreReportedWithoutHidingTheOthers() {
        var files = new FakeServer("files", "read_file");
        var git = new FakeServer("git");
        git.down();
        var registry = new ToolRegistry(List.of(files.connection, git.connection));

        ToolRegistry.Catalog catalog = registry.tools(false);

        assertThat(catalog.tools()).extracting(McpTool::name).containsExactly("read_file");
        assertThat(catalog.unavailable()).extracting(McpUnavailableException::server).containsExactly("git");
        assertThat(registry.find("read_file")).isPresent();
    }

    @Test
    void unknownNameIsEmptyWhenAllServersAnsweredAndAnOutageOtherwise() {
        var files = new FakeServer("files", "read_file");
        var git = new FakeServer("git");
        var registry = new ToolRegistry(List.of(files.connection, git.connection));
        assertThat(registry.find("git_log")).isEmpty();

        git.down();
        git.version.incrementAndGet();
        assertThatThrownBy(() -> registry.find("git_log")).isInstanceOf(McpUnavailableException.class)
                .hasMessageContaining("git down");
    }

    @Test
    void cachedToolsNeverContactTheServer() {
        var files = new FakeServer("files", "read_file");
        var registry = new ToolRegistry(List.of(files.connection));

        assertThat(registry.cachedTools("files")).isEmpty();
        registry.tools(false);
        assertThat(registry.cachedTools("files")).extracting(McpTool::name).containsExactly("read_file");
        verify(files.connection, times(1)).call(eq("tools/list"), any(), eq(true));
    }

    @Test
    void closeClosesEveryConnection() {
        var files = new FakeServer("files");
        var git = new FakeServer("git");

        new ToolRegistry(List.of(files.connection, git.connection)).close();

        verify(files.connection).close();
        verify(git.connection).close();
        verify(files.connection, never()).call(any(), any(), eq(true));
    }

    /** A mocked connection whose tools/list returns fixed tools, or fails once {@link #down()} is called. */
    private static final class FakeServer {
        final McpConnection connection = mock(McpConnection.class);
        final McpSession session = mock(McpSession.class);
        final AtomicLong version = new AtomicLong(1);
        private boolean down;

        @SuppressWarnings("unchecked")
        FakeServer(String name, String... tools) {
            when(connection.name()).thenReturn(name);
            when(connection.toolsVersion()).thenAnswer(i -> version.get());
            when(session.listTools()).thenReturn(List.of(tools).stream()
                    .map(tool -> new McpTool(name, tool, "", Map.of(), true)).toList());
            when(connection.call(eq("tools/list"), any(), eq(true))).thenAnswer(invocation -> {
                if (down) {
                    throw new McpUnavailableException(McpErrorCode.CONNECTION_FAILED, name, name + " down", null);
                }
                return ((Function<McpSession, Object>) invocation.getArgument(1)).apply(session);
            });
        }

        void down() {
            down = true;
        }

        int listCalls() {
            return org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("listTools")).mapToInt(i -> 1).sum();
        }
    }
}
