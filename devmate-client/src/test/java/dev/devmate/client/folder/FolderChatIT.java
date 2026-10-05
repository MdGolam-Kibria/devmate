package dev.devmate.client.folder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.devmate.client.llm.LlmClient;
import dev.devmate.client.llm.LlmException;
import dev.devmate.client.llm.LlmRequest;
import dev.devmate.client.llm.LlmResponse;
import dev.devmate.client.llm.LlmToolCall;
import dev.devmate.client.mcp.McpTool;
import dev.devmate.client.mcp.McpToolClient;
import dev.devmate.client.mcp.McpToolResult;
import dev.devmate.client.mcp.McpUnavailableException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Folder Q&A through the web UI and the JSON API, with the MCP server and the model replaced by mocks. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"devmate.llm.provider=ollama", "devmate.llm.model=llama3.2:3b"})
class FolderChatIT {

    private static final String FOLDER = "/work/project";
    private static final String LISTING = """
            {"path":"/work/project","entries":[{"name":"src","path":"/work/project/src","type":"DIRECTORY","size":0}],
             "truncated":false}""";

    /** Local folders the picker browses ({@code devmate.ui.browse-start}). */
    private static final Path HOME = homeTree();

    @MockitoBean
    McpToolClient mcp;

    @MockitoBean
    LlmClient llm;

    @Value("${local.server.port}")
    int port;

    private HttpClient browser;

    @DynamicPropertySource
    static void browseStart(DynamicPropertyRegistry registry) {
        registry.add("devmate.ui.browse-start", HOME::toString);
    }

    @BeforeEach
    void setUp() {
        browser = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        when(mcp.callTool("list_files", Map.of("path", FOLDER))).thenReturn(new McpToolResult(LISTING, false));
        when(mcp.callTool("list_files", Map.of())).thenReturn(new McpToolResult(
                "{\"path\":null,\"entries\":[{\"name\":\"/work\",\"path\":\"/work\",\"type\":\"DIRECTORY\"}]}", false));
        when(mcp.listTools()).thenReturn(List.of(new McpTool("search_code", "Find text",
                Map.of("type", "object", "properties", Map.of("query", Map.of(), "path", Map.of())), true)));
    }

    @Test
    void withoutAFolderThePageIsPlainChat() throws Exception {
        String page = get("/").body();

        assertThat(page).contains("No folder open: plain chat.", "Open a folder").doesNotContain("folder-path");
        verify(mcp, never()).callTool(any(), anyMap());
    }

    @Test
    void pickerSuggestsTheServersRoots() throws Exception {
        String page = get("/folder").body();

        assertThat(page).contains("name=\"path\"", "class=\"root-chip\"", "value=\"/work\"");
    }

    @Test
    void pickerBrowsesTheHomeFolderByDefault() throws Exception {
        String page = get("/folder").body();

        assertThat(page).contains("id=\"browse-path\">" + HOME + "<", "Open this folder",
                        "name=\"path\" value=\"" + HOME + "\"", ">projects<", ">notes<", "id=\"dir-up\"")
                .doesNotContain(">.hidden<", "todo.txt");
        assertThat(page.indexOf(">notes<")).isLessThan(page.indexOf(">projects<"));
    }

    @Test
    void clickingAFolderBrowsesIntoItWithABreadcrumb() throws Exception {
        Path projects = HOME.resolve("projects");

        String page = get("/folder?browse=" + enc(projects.toString())).body();

        assertThat(page).contains("id=\"browse-path\">" + projects + "<", ">shop<", "aria-current=\"location\">projects<",
                "name=\"path\" value=\"" + projects.resolve("shop") + "\"");
        verify(mcp, never()).callTool(eq("list_files"), eq(Map.of("path", projects.toString())));
    }

    @Test
    void openThisFolderOpensTheBrowsedFolder() throws Exception {
        String shop = HOME.resolve("projects/shop").toString();
        when(mcp.callTool("list_files", Map.of("path", shop))).thenReturn(new McpToolResult(
                "{\"path\":\"" + shop + "\",\"entries\":[],\"truncated\":false}", false));

        assertThat(get("/folder?browse=" + enc(shop)).body()).contains("name=\"path\" value=\"" + shop + "\"");
        assertThat(post("/folder", "path=" + enc(shop)).body()).contains("id=\"folder-path\">" + shop + "<");
    }

    @Test
    void unbrowsableFolderShowsWhyAndFallsBackToHome() throws Exception {
        String page = get("/folder?browse=" + enc(HOME.resolve("todo.txt").toString())).body();

        assertThat(page).contains("Not a folder", "id=\"browse-path\">" + HOME + "<");
        assertThat(get("/folder?browse=relative").body()).contains("Enter an absolute path");
    }

    @Test
    void afterARefusedOpenThePickerShowsThatFolder() throws Exception {
        String shop = HOME.resolve("projects/shop").toString();
        when(mcp.callTool("list_files", Map.of("path", shop)))
                .thenReturn(new McpToolResult("ACCESS_DENIED: path is excluded by policy: shop", true));

        assertThat(post("/folder", "path=" + enc(shop)).body())
                .contains("excluded by policy", "id=\"browse-path\">" + shop + "<");
    }

    @Test
    void pickerSaysWhenTheServerIsDown() throws Exception {
        when(mcp.callTool("list_files", Map.of())).thenThrow(new McpUnavailableException("Files MCP server is down", null));

        assertThat(get("/folder").body()).contains("Files MCP server is down", "name=\"path\"");
    }

    @Test
    void openingAFolderShowsItAndQuestionsUseItsTools() throws Exception {
        when(llm.chat(any())).thenReturn(new LlmResponse("Kafka is used in `src/Order.java`.", "llama3.2:3b", 100, 20,
                List.of(new LlmToolCall("search_code", "{\"query\":\"Kafka\"}", false))));

        String opened = post("/folder", "path=" + enc(FOLDER)).body();
        assertThat(opened).contains("id=\"folder-path\">/work/project<", "1 entry", "Ask about this folder");

        String answered = post("/chat", "message=" + enc("Where is Kafka used?")).body();
        assertThat(answered)
                .contains("<code>src/Order.java</code>", "Tools used (1)",
                        "search_code {&quot;query&quot;:&quot;Kafka&quot;}", "1 tool call");
        verify(llm).chat(argThat((LlmRequest r) -> r.tools().size() == 1
                && r.systemPrompt().contains(FOLDER) && r.userMessage().equals("Where is Kafka used?")));
    }

    @Test
    void rejectedFolderShowsTheServersReasonAndKeepsPlainChat() throws Exception {
        when(mcp.callTool("list_files", Map.of("path", "/etc")))
                .thenReturn(new McpToolResult("ACCESS_DENIED: path is outside the allowed roots", true));

        var page = post("/folder", "path=" + enc("/etc"));

        assertThat(page.uri().getPath()).isEqualTo("/folder");
        assertThat(page.body()).contains("ACCESS_DENIED: path is outside the allowed roots", "value=\"/etc\"")
                .doesNotContain("id=\"folder-path\"");
    }

    @Test
    void folderOutsideTheAllowedRootsAsksForApproval() throws Exception {
        when(mcp.callTool("list_files", Map.of("path", "/elsewhere")))
                .thenReturn(new McpToolResult("ACCESS_DENIED: path is outside the allowed roots", true));

        String page = post("/folder", "path=" + enc("/elsewhere")).body();

        assertThat(page).contains("id=\"approve-dialog\"", "Allow DevMate to read this folder?",
                ">/elsewhere<", "action=\"/folder/allow\"", "name=\"path\" value=\"/elsewhere\"",
                "What it reads stays on this machine");
        verify(mcp, never()).approveRoot(any());
    }

    @Test
    void otherRefusalsDoNotOfferApproval() throws Exception {
        when(mcp.callTool("list_files", Map.of("path", "/work/project/.git")))
                .thenReturn(new McpToolResult("ACCESS_DENIED: path is excluded by policy: .git", true));

        assertThat(post("/folder", "path=" + enc("/work/project/.git")).body())
                .contains("excluded by policy").doesNotContain("approve-dialog");
    }

    @Test
    void allowOpensTheFolder() throws Exception {
        when(mcp.callTool("list_files", Map.of("path", "/elsewhere")))
                .thenReturn(new McpToolResult("{\"path\":\"/elsewhere\",\"entries\":[],\"truncated\":false}", false));

        var page = post("/folder/allow", "path=" + enc("/elsewhere"));

        assertThat(page.uri().getPath()).isEqualTo("/");
        assertThat(page.body()).contains("id=\"folder-path\">/elsewhere<").doesNotContain("approve-dialog");
        verify(mcp).approveRoot(Path.of("/elsewhere"));
    }

    @Test
    void allowRefusedByTheServerExplainsTheSetting() throws Exception {
        when(mcp.callTool("list_files", Map.of("path", "/elsewhere")))
                .thenReturn(new McpToolResult("ACCESS_DENIED: path is outside the allowed roots", true));

        String page = post("/folder/allow", "path=" + enc("/elsewhere")).body();

        assertThat(page).contains("DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=true").doesNotContain("id=\"folder-path\"");
        verify(mcp).revokeRoot(Path.of("/elsewhere"));
    }

    @Test
    void relativePathIsRejectedWithoutCallingTheServer() throws Exception {
        assertThat(post("/folder", "path=project").body()).contains("INVALID_ARGUMENT: enter an absolute path");
        verify(mcp, never()).callTool(eq("list_files"), eq(Map.of("path", "project")));
    }

    @Test
    void closingTheFolderReturnsToPlainChat() throws Exception {
        when(llm.chat(any())).thenReturn(new LlmResponse("plain", "m", null, null));
        post("/folder", "path=" + enc(FOLDER));

        assertThat(post("/folder/close", "").body()).contains("No folder open: plain chat.");
        post("/chat", "message=hi");
        verify(llm).chat(LlmRequest.of("hi"));
    }

    @Test
    void mcpOutageWhileAskingIsShownAsAnError() throws Exception {
        post("/folder", "path=" + enc(FOLDER));
        when(mcp.listTools()).thenThrow(new McpUnavailableException("Files MCP server is not reachable at x", null));

        assertThat(post("/chat", "message=q").body()).contains("message error", "Files MCP server is not reachable at x");
    }

    @Test
    void folderIsPerSession() throws Exception {
        post("/folder", "path=" + enc(FOLDER));

        HttpClient other = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertThat(other.send(request("/").GET().build(), HttpResponse.BodyHandlers.ofString()).body())
                .contains("No folder open");
    }

    @Test
    void apiAnswersWithToolCalls() throws Exception {
        when(llm.chat(any())).thenReturn(new LlmResponse("in src", "llama3.2:3b", 5, 6,
                List.of(new LlmToolCall("search_code", "{}", false))));

        var response = postJson("/api/folder/ask", "{\"folder\":\"/work/project\",\"question\":\"Where?\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"reply\":\"in src\"", "\"provider\":\"ollama\"", "\"name\":\"search_code\"",
                "\"folder\":\"/work/project\"");
    }

    @Test
    void apiMapsFailuresToStatusCodes() throws Exception {
        when(mcp.callTool("list_files", Map.of("path", "/etc")))
                .thenReturn(new McpToolResult("ACCESS_DENIED: path is outside the allowed roots", true));
        when(mcp.callTool("list_files", Map.of("path", "/work/gone")))
                .thenReturn(new McpToolResult("NOT_FOUND: no such file or directory: /work/gone", true));

        assertThat(postJson("/api/folder/ask", "{\"folder\":\"/etc\",\"question\":\"q\"}").statusCode()).isEqualTo(403);
        assertThat(postJson("/api/folder/ask", "{\"folder\":\"/work/gone\",\"question\":\"q\"}").statusCode()).isEqualTo(404);
        assertThat(postJson("/api/folder/ask", "{\"folder\":\"rel\",\"question\":\"q\"}").statusCode()).isEqualTo(400);
        assertThat(postJson("/api/folder/ask", "{\"folder\":\"/work/project\",\"question\":\" \"}").statusCode())
                .isEqualTo(400);

        when(llm.chat(any())).thenThrow(new LlmException("LLM provider 'ollama' failed: x", null));
        assertThat(postJson("/api/folder/ask", "{\"folder\":\"/work/project\",\"question\":\"q\"}").statusCode())
                .isEqualTo(502);

        when(mcp.callTool("list_files", Map.of("path", FOLDER))).thenThrow(new McpUnavailableException("down", null));
        var down = postJson("/api/folder/ask", "{\"folder\":\"/work/project\",\"question\":\"q\"}");
        assertThat(down.statusCode()).isEqualTo(503);
        assertThat(down.body()).contains("MCP_UNAVAILABLE");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return browser.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return browser.send(request(path).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return browser.send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Path homeTree() {
        try {
            Path home = Files.createTempDirectory("devmate-picker-it").toRealPath();
            Files.createDirectories(home.resolve("projects/shop/src"));
            Files.createDirectories(home.resolve("notes"));
            Files.createDirectories(home.resolve(".hidden"));
            Files.writeString(home.resolve("todo.txt"), "not a folder");
            return home;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
