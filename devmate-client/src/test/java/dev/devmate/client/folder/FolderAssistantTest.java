package dev.devmate.client.folder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.devmate.client.agent.AgentProperties;
import dev.devmate.client.agent.DevMateAgent;
import dev.devmate.client.llm.LlmClient;
import dev.devmate.client.llm.LlmMessage;
import dev.devmate.client.llm.LlmRequest;
import dev.devmate.client.llm.LlmResponse;
import dev.devmate.client.llm.LlmTool;
import dev.devmate.client.mcp.McpClientService;
import dev.devmate.client.mcp.McpErrorCode;
import dev.devmate.client.mcp.McpTool;
import dev.devmate.client.mcp.McpToolResult;
import dev.devmate.client.mcp.McpUnavailableException;
import dev.devmate.client.mcp.ToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** Folder Q&amp;A end to end through a real {@link DevMateAgent}, with the MCP client and the model mocked. */
class FolderAssistantTest {

    private static final String FOLDER = "/work/project";
    private static final String LISTING = """
            {"path":"/work/project","entries":[
              {"name":"src","path":"/work/project/src","type":"DIRECTORY","size":0},
              {"name":"pom.xml","path":"/work/project/pom.xml","type":"FILE","size":10}],"truncated":false}""";
    private static final Map<String, Object> PATH_SCHEMA =
            Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")));

    private final McpClientService mcp = mock(McpClientService.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final AgentProperties limits = new AgentProperties(5, 20, 60, 12, 16000);
    private final FolderAssistant assistant = new FolderAssistant(mcp,
            new DevMateAgent(mcp, llm, limits, json, new SimpleMeterRegistry()), limits, json);

    @BeforeEach
    void folderExists() {
        when(mcp.tools(anyBoolean())).thenAnswer(call -> new ToolRegistry.Catalog(mcp.listTools(), List.of()));
        when(mcp.callTool("list_files", Map.of("path", FOLDER))).thenReturn(McpToolResult.success(LISTING));
        when(llm.chat(any())).thenReturn(new LlmResponse("answer", "m", 1, 1));
    }

    @Test
    void openReturnsTheServersRealPathAndEntries() {
        FolderSnapshot snapshot = assistant.open("  " + FOLDER + "  ");

        assertThat(snapshot).isEqualTo(new FolderSnapshot(FOLDER, List.of("src/", "pom.xml"), false));
    }

    @Test
    void openPassesTheServersRefusalThrough() {
        when(mcp.callTool("list_files", Map.of("path", "/etc")))
                .thenReturn(new McpToolResult("ACCESS_DENIED: path is outside the allowed roots",
                        McpErrorCode.TOOL_ERROR));

        assertThatThrownBy(() -> assistant.open("/etc"))
                .isInstanceOf(FolderException.class).hasMessage("ACCESS_DENIED: path is outside the allowed roots");
    }

    @Test
    void openRejectsBlankAndRelativePathsWithoutCallingTheServer() {
        assertThatThrownBy(() -> assistant.open(" ")).hasMessageStartingWith("INVALID_ARGUMENT: enter the absolute");
        assertThatThrownBy(() -> assistant.open("project")).hasMessageStartingWith("INVALID_ARGUMENT: enter an absolute");
        verify(mcp, never()).callTool(any(), anyMap());
    }

    @Test
    void allowDeclaresTheFolderAsARootAndOpensIt() {
        assertThat(assistant.allow(FOLDER).path()).isEqualTo(FOLDER);

        var order = inOrder(mcp);
        order.verify(mcp).approveRoot(Path.of(FOLDER));
        order.verify(mcp).callTool("list_files", Map.of("path", FOLDER));
        verify(mcp, never()).revokeRoot(any());
    }

    @Test
    void allowNotAcceptedByTheServerIsRevokedAndExplained() {
        when(mcp.callTool("list_files", Map.of("path", "/other")))
                .thenReturn(new McpToolResult("ACCESS_DENIED: path is outside the allowed roots",
                        McpErrorCode.TOOL_ERROR));

        assertThatThrownBy(() -> assistant.allow("/other"))
                .isInstanceOf(FolderException.class)
                .hasMessageContaining("did not accept the approval")
                .hasMessageContaining("DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=true");
        verify(mcp).revokeRoot(Path.of("/other"));
    }

    @Test
    void allowRejectsRelativePathsWithoutApproving() {
        assertThatThrownBy(() -> assistant.allow("other")).hasMessageStartingWith("INVALID_ARGUMENT");
        verify(mcp, never()).approveRoot(any());
    }

    @Test
    void onlyTheOutsideRootsRefusalCanBeApproved() {
        assertThat(FolderAssistant.isOutsideAllowedRoots(
                new FolderException("ACCESS_DENIED: path is outside the allowed roots"))).isTrue();
        assertThat(FolderAssistant.isOutsideAllowedRoots(
                new FolderException("ACCESS_DENIED: path is excluded by policy: /x/.git"))).isFalse();
        assertThat(FolderAssistant.isOutsideAllowedRoots(new FolderException("NOT_FOUND: /x"))).isFalse();
    }

    @Test
    void rootsComeFromListFilesWithoutPath() {
        when(mcp.callTool("list_files", Map.of())).thenReturn(McpToolResult.success(
                "{\"path\":null,\"entries\":[{\"name\":\"/a\",\"path\":\"/a\",\"type\":\"DIRECTORY\",\"size\":0}]}"));

        assertThat(assistant.roots()).containsExactly("/a");
    }

    @Test
    void offersOnlyReadOnlyToolsThatTakeAPath() {
        when(mcp.listTools()).thenReturn(List.of(
                new McpTool("files", "read_file", "Read a file.", PATH_SCHEMA, true),
                new McpTool("files", "delete_file", "Delete a file.", PATH_SCHEMA, false),
                new McpTool("files", "server_time", "Time.", Map.of("type", "object"), true)));

        assistant.ask(FOLDER, "What is in pom.xml?",
                List.of(LlmMessage.user("hi"), LlmMessage.assistant("hello")));

        LlmRequest request = sentRequest();
        assertThat(request.tools()).extracting(LlmTool::name).containsExactly("read_file");
        assertThat(request.tools().getFirst().description()).startsWith("Read a file.")
                .contains("relative to the selected folder");
        assertThat(request.tools().getFirst().inputSchema()).contains("\"path\"");
        assertThat(request.maxToolRounds()).isEqualTo(5);
        assertThat(request.userMessage()).isEqualTo("What is in pom.xml?");
        assertThat(request.systemPrompt()).contains(FOLDER, "- src/", "- pom.xml");
        assertThat(request.history()).containsExactly(LlmMessage.user("hi"), LlmMessage.assistant("hello"));
    }

    @Test
    void toolCallsArePinnedToTheFolder() {
        when(mcp.listTools()).thenReturn(List.of(new McpTool("files", "read_file", "", PATH_SCHEMA, true)));
        when(mcp.callTool(eq("read_file"), anyMap())).thenReturn(McpToolResult.success("content"));
        assistant.ask(FOLDER, "q", List.of());
        LlmTool tool = sentRequest().tools().getFirst();

        assertThat(tool.handler().apply("{\"path\":\"src/A.java\"}")).isEqualTo(LlmTool.Output.ok("content"));
        verify(mcp).callTool("read_file", Map.of("path", "/work/project/src/A.java"));

        assertThat(tool.handler().apply("{\"path\":\"../other/secret\"}").text())
                .startsWith("ACCESS_DENIED: path is outside the selected folder");
        verify(mcp, never()).callTool("read_file", Map.of("path", "/work/other/secret"));

        assertThat(tool.handler().apply("not json")).isEqualTo(
                LlmTool.Output.error("INVALID_ARGUMENT: arguments must be a JSON object"));
    }

    @Test
    void longToolOutputIsCutOff() {
        when(mcp.listTools()).thenReturn(List.of(new McpTool("files", "read_file", "", PATH_SCHEMA, true)));
        when(mcp.callTool(eq("read_file"), anyMap())).thenReturn(McpToolResult.success("x".repeat(50)));
        assistant.ask(FOLDER, "q", List.of());

        String text = sentRequest().tools().getFirst().handler().apply("{}").text();

        assertThat(text).startsWith("x".repeat(20) + "\n…[output cut off by DevMate after 20 characters]");
    }

    @Test
    void toolErrorsFromTheServerReachTheModelAsErrors() {
        when(mcp.listTools()).thenReturn(List.of(new McpTool("files", "read_file", "", PATH_SCHEMA, true)));
        when(mcp.callTool(eq("read_file"), anyMap()))
                .thenReturn(new McpToolResult("ACCESS_DENIED: .env", McpErrorCode.TOOL_ERROR));
        assistant.ask(FOLDER, "q", List.of());

        assertThat(sentRequest().tools().getFirst().handler().apply("{\"path\":\".env\"}"))
                .isEqualTo(LlmTool.Output.error("ACCESS_DENIED: .env"));
    }

    @Test
    void toolOutcomeSummaryKeepsCountsAndFlagsButNoContent() {
        when(mcp.listTools()).thenReturn(List.of(new McpTool("files", "search_code", "", PATH_SCHEMA, true)));
        when(mcp.callTool(eq("search_code"), anyMap())).thenReturn(McpToolResult.success(
                "{\"query\":\"q\",\"matches\":[{\"path\":\"/secret\"}],\"filesScanned\":4,\"truncated\":false}"));
        assistant.ask(FOLDER, "q", List.of());

        LlmTool.Output output = sentRequest().tools().getFirst().handler().apply("{}");

        assertThat(output.summary())
                .isEqualTo("matches=1 filesScanned=4 truncated=false (cut off by DevMate at 20 chars)");
    }

    @Test
    void systemPromptCarriesTheFolderOverviewWhenTheServerHasIt() {
        when(mcp.listTools()).thenReturn(List.of(new McpTool("files", "folder_overview", "", PATH_SCHEMA, true)));
        when(mcp.callTool("folder_overview", Map.of("path", FOLDER))).thenReturn(McpToolResult.success("""
                {"path":"/work/project","files":42,"directories":7,"totalBytes":204800,"truncated":false,
                 "fileTypes":[{"extension":"java","files":30,"bytes":1},{"extension":"yml","files":2,"bytes":1}],
                 "projectFiles":[{"path":"/work/project/pom.xml","kind":"Maven build"},
                                 {"path":"/work/project/src/main/resources/application.yml","kind":"Spring config"}],
                 "largestFiles":[],
                 "recentlyModified":[{"path":"/work/project/src/A.java","modified":"2026-10-01T00:00:00Z"}]}"""));

        assistant.ask(FOLDER, "q", List.of());

        assertThat(sentRequest().systemPrompt()).contains("""
                Folder overview (secrets and build/VCS directories excluded):
                42 files in 7 directories, 200.0 KB
                File types: java (30), yml (2)
                Project files: pom.xml (Maven build), src/main/resources/application.yml (Spring config)
                Recently modified: src/A.java
                """);
    }

    @Test
    void overviewIsLeftOutWhenTheServerLacksOrRefusesIt() {
        when(mcp.listTools()).thenReturn(List.of(new McpTool("files", "read_file", "", PATH_SCHEMA, true)));
        assistant.ask(FOLDER, "q", List.of());
        assertThat(sentRequest().systemPrompt()).doesNotContain("Folder overview");
        verify(mcp, never()).callTool(eq("folder_overview"), anyMap());

        when(mcp.callTool(eq("folder_overview"), anyMap()))
                .thenReturn(new McpToolResult("ACCESS_DENIED: x", McpErrorCode.TOOL_ERROR));
        assertThat(assistant.overview(FOLDER)).isNull();
        when(mcp.callTool(eq("folder_overview"), anyMap())).thenReturn(McpToolResult.success("not json"));
        assertThat(assistant.overview(FOLDER)).isNull();
    }

    @Test
    void serverOutageAbortsTheAnswer() {
        when(mcp.callTool("list_files", Map.of("path", FOLDER)))
                .thenThrow(down("Files MCP server is not reachable"));

        assertThatThrownBy(() -> assistant.ask(FOLDER, "q", List.of())).isInstanceOf(McpUnavailableException.class);
        verify(llm, never()).chat(any());
    }

    private LlmRequest sentRequest() {
        var request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).chat(request.capture());
        return request.getValue();
    }

    private static McpUnavailableException down(String message) {
        return new McpUnavailableException(McpErrorCode.CONNECTION_FAILED, "files", message, null);
    }
}
