package dev.devmate.client.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

/**
 * A real MCP server (official SDK, Streamable HTTP, embedded Tomcat) for client tests, so the client is tested over
 * the actual protocol without depending on the files MCP module. Counts calls per tool, and can be told to forget its
 * sessions once (the next request with a session id gets 404).
 */
public final class TestMcpServer implements AutoCloseable {

    static final Map<String, Object> PATH_SCHEMA =
            Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")));
    static final Map<String, Object> QUERY_SCHEMA = Map.of("type", "object",
            "properties", Map.of("query", Map.of("type", "string", "minLength", 1)), "required", List.of("query"));

    private final String name;
    private final boolean announceChanges;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final AtomicInteger forgetSessions = new AtomicInteger();
    private WebServer web;
    private McpSyncServer mcp;
    private int port;

    private TestMcpServer(String name, boolean announceChanges) {
        this.name = name;
        this.announceChanges = announceChanges;
    }

    /** A server with the standard tools; {@code announceChanges} = tools capability {@code listChanged}. */
    public static TestMcpServer start(String name, boolean announceChanges, SyncToolSpecification... tools) {
        var server = new TestMcpServer(name, announceChanges);
        server.startOn(0, tools);
        return server;
    }

    void startOn(int requestedPort, SyncToolSpecification... tools) {
        var transport = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
        mcp = McpServer.sync(transport)
                .serverInfo(name, "1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(announceChanges).build())
                .tools(tools)
                .build();
        var factory = new TomcatServletWebServerFactory(requestedPort);
        factory.setAddress(InetAddress.getLoopbackAddress());
        web = factory.getWebServer(context -> {
            context.addFilter("forget-sessions", (request, response, chain) -> {
                if (((HttpServletRequest) request).getHeader("Mcp-Session-Id") != null
                        && forgetSessions.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    ((HttpServletResponse) response).sendError(404);
                    return;
                }
                chain.doFilter(request, response);
            }).addMappingForUrlPatterns(null, false, "/mcp");
            var servlet = context.addServlet("mcp", transport);
            servlet.addMapping("/mcp", "/mcp/*");
            servlet.setAsyncSupported(true);
        });
        web.start();
        port = web.getPort();
    }

    void stop() {
        if (mcp != null) {
            mcp.close();
            mcp = null;
        }
        if (web != null) {
            web.stop();
            web = null;
        }
    }

    /** Restarts on the same port with these tools: all sessions are gone. */
    public void restart(SyncToolSpecification... tools) {
        stop();
        startOn(port, tools);
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + port + "/mcp");
    }

    public int calls(String tool) {
        return calls.computeIfAbsent(tool, t -> new AtomicInteger()).get();
    }

    McpSyncServer mcp() {
        return mcp;
    }

    /** The next request that carries a session id gets 404, as if the server had expired the session. */
    void forgetSessionsOnce() {
        forgetSessions.set(1);
    }

    @Override
    public void close() {
        stop();
    }

    // ── tools ─────────────────────────────────────────────────────────────────────────────────────

    /** A tool whose handler maps the arguments to a result, counting calls. */
    SyncToolSpecification tool(String toolName, boolean readOnly, Map<String, Object> schema,
                                BiFunction<String, Map<String, Object>, CallToolResult> handler) {
        var tool = McpSchema.Tool.builder().name(toolName).description(toolName + " tool").inputSchema(schema)
                .annotations(new McpSchema.ToolAnnotations(null, readOnly, !readOnly, true, false, null))
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            calls.computeIfAbsent(toolName, t -> new AtomicInteger()).incrementAndGet();
            return handler.apply(toolName, request.arguments());
        }).build();
    }

    /** read_file: "content of &lt;path&gt;", or NOT_FOUND (isError) for "missing". */
    public SyncToolSpecification readFile() {
        return tool("read_file", true, PATH_SCHEMA, (t, args) -> "missing".equals(args.get("path"))
                ? CallToolResult.builder().addTextContent("NOT_FOUND: missing").isError(true).build()
                : text("content of " + args.get("path")));
    }

    /** search_code(query): fixed Kafka matches, like the files MCP server's JSON. */
    public SyncToolSpecification searchCode() {
        return tool("search_code", true, QUERY_SCHEMA, (t, args) -> text(
                "Kafka".equalsIgnoreCase(String.valueOf(args.get("query")))
                        ? "{\"query\":\"Kafka\",\"matches\":[{\"path\":\"/w/PaymentProducer.java\",\"line\":3},"
                          + "{\"path\":\"/w/KafkaConfig.java\",\"line\":7}],\"filesScanned\":12,\"truncated\":false}"
                        : "{\"query\":\"" + args.get("query") + "\",\"matches\":[],\"filesScanned\":12}"));
    }

    public SyncToolSpecification writeFile() {
        return tool("write_file", false, PATH_SCHEMA, (t, args) -> text("written"));
    }

    /** Echoes the client's roots, as the server sees them through roots/list. */
    SyncToolSpecification rootsTool() {
        var tool = McpSchema.Tool.builder().name("roots").description("Client roots")
                .inputSchema(Map.of("type", "object"))
                .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, null))
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> text(
                exchange.listRoots().roots().stream().map(McpSchema.Root::uri).collect(Collectors.joining(","))))
                .build();
    }

    /** Sleeps before answering. */
    SyncToolSpecification slow(long millis) {
        return tool("slow", true, Map.of("type", "object"), (t, args) -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return text("finally");
        });
    }

    /** Answers with a JSON-RPC error. */
    SyncToolSpecification failing(String toolName, int code, String message) {
        return tool(toolName, true, Map.of("type", "object"), (t, args) -> {
            throw McpError.builder(code).message(message).build();
        });
    }

    /** Returns this many characters of text. */
    SyncToolSpecification big(int chars) {
        return tool("big", true, Map.of("type", "object"), (t, args) -> text("x".repeat(chars)));
    }

    static CallToolResult text(String text) {
        return CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    public static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
