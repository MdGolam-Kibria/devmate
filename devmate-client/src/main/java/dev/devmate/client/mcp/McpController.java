package dev.devmate.client.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON API over the MCP client, without any LLM: server status, discovered tools, and calling a tool directly
 * (scripts, curl, debugging a server). Calls go through the same {@link ToolExecutor} policy as the model's: read-only
 * tools only, arguments checked against the schema. POST accepts {@code application/json} only, which a cross-site
 * form can't send.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    /** MCP tool names: 1-128 of {@code A-Z a-z 0-9 _ - .} (spec 2025-11-25). */
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    public record CallRequest(Map<String, Object> arguments) {}

    public record CallReply(String tool, String server, boolean error, McpErrorCode errorCode, String text,
                            long durationMs) {}

    public record ToolsReply(List<McpTool> tools, List<Map<String, Object>> unavailable) {}

    private final McpClientService mcp;

    public McpController(McpClientService mcp) {
        this.mcp = mcp;
    }

    @GetMapping("/servers")
    public Map<String, List<McpServerStatus>> servers() {
        return Map.of("servers", mcp.servers());
    }

    @GetMapping("/tools")
    public ToolsReply tools(@RequestParam(defaultValue = "false") boolean refresh) {
        ToolRegistry.Catalog catalog = mcp.tools(refresh);
        return new ToolsReply(catalog.tools(), catalog.unavailable().stream().map(McpController::outage).toList());
    }

    @PostMapping(path = "/tools/{name}/call", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> call(@PathVariable String name, @RequestBody CallRequest request) {
        if (!TOOL_NAME.matcher(name).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST",
                    "message", "tool name must be 1-128 characters of A-Z a-z 0-9 _ - ."));
        }
        long start = System.nanoTime();
        McpToolResult result = mcp.callTool(name, request.arguments());
        var reply = new CallReply(name, mcp.serverOf(name).orElse(null), result.error(), result.errorCode(),
                result.text(), (System.nanoTime() - start) / 1_000_000);
        return ResponseEntity.status(status(result.errorCode())).body(reply);
    }

    /** A tool's own failure is still a completed call (200); DevMate's refusals and server faults are not. */
    private static HttpStatus status(McpErrorCode code) {
        return code == null ? HttpStatus.OK : switch (code) {
            case TOOL_ERROR -> HttpStatus.OK;
            case UNKNOWN_TOOL -> HttpStatus.NOT_FOUND;
            case INVALID_ARGUMENTS -> HttpStatus.BAD_REQUEST;
            case TOOL_NOT_ALLOWED -> HttpStatus.FORBIDDEN;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    @ExceptionHandler(McpUnavailableException.class)
    ResponseEntity<Map<String, Object>> unavailable(McpUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(outage(e));
    }

    private static Map<String, Object> outage(McpUnavailableException e) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", "MCP_UNAVAILABLE");
        body.put("code", e.code());
        body.put("server", e.server());
        body.put("message", e.getMessage());
        return body;
    }
}
