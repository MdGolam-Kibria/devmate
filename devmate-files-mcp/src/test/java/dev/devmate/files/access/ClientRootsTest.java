package dev.devmate.files.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.devmate.files.TestWorkspace;
import dev.devmate.files.config.FilesProperties;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

class ClientRootsTest {

    @TempDir
    Path tmp;

    private TestWorkspace ws;
    private FileAccessPolicy policy;
    private final McpSyncRequestContext context = mock(McpSyncRequestContext.class);

    @BeforeEach
    void setUp() {
        ws = TestWorkspace.create(tmp);
        policy = new FileAccessPolicy(ws.properties());
        when(context.rootsEnabled()).thenReturn(true);
        when(context.roots()).thenReturn(new McpSchema.ListRootsResult(
                List.of(new McpSchema.Root(ws.outside().toUri().toString(), "outside")), null));
    }

    @Test
    void disabledByDefaultAndNeverAsksTheClient() {
        assertThat(clientRoots(false).policyFor(context)).isSameAs(policy);
        verify(context, never()).roots();
    }

    @Test
    void enabledAddsTheClientsRoots() throws Exception {
        assertThat(clientRoots(true).policyFor(context).roots()).contains(ws.outside().toRealPath());
    }

    @Test
    void noContextOrNoRootsCapabilityMeansConfiguredRootsOnly() {
        when(context.rootsEnabled()).thenReturn(false);
        assertThat(clientRoots(true).policyFor(context)).isSameAs(policy);
        assertThat(clientRoots(true).policyFor(null)).isSameAs(policy);
    }

    @Test
    void failingRootsRequestFallsBackToConfiguredRoots() {
        when(context.roots()).thenThrow(new IllegalStateException("client went away"));
        assertThat(clientRoots(true).policyFor(context)).isSameAs(policy);
    }

    @Test
    void onlyFileUrisCount() {
        assertThat(ClientRoots.toPaths(List.of(new McpSchema.Root("https://example.com/x", "web"),
                new McpSchema.Root("not a uri", "bad"), new McpSchema.Root("file:///tmp/a%20b", "space"))))
                .containsExactly(Path.of("/tmp/a b"));
    }

    private ClientRoots clientRoots(boolean enabled) {
        FilesProperties p = ws.properties();
        return new ClientRoots(policy, new FilesProperties(p.allowedRoots(), p.maxFileSize(), p.maxListEntries(),
                p.maxSearchResults(), p.maxSearchFiles(), p.maxLineLength(), p.excludedDirectories(), p.deniedFiles(),
                enabled));
    }
}
