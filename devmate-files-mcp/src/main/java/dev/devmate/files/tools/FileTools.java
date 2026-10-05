package dev.devmate.files.tools;

import dev.devmate.files.access.FileAccessException;
import dev.devmate.files.service.FileService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP adapter: declares the tools (input schemas are generated from these signatures) and delegates to
 * {@link FileService}. Results are built here rather than by the framework so the wire format is ours: success is
 * one JSON text block; failure is {@code isError=true} with a single {@code REASON: detail} line (see
 * {@link FileAccessException}). Unexpected failures never leak their message.
 */
@Component
public class FileTools {

    private static final Logger log = LoggerFactory.getLogger(FileTools.class);

    private final FileService files;
    private final JsonMapper json;

    public FileTools(FileService files, JsonMapper json) {
        this.files = files;
        this.json = json;
    }

    @McpTool(name = "list_files",
            description = """
                    List the entries of a directory (directories first). With no path, lists the allowed root \
                    directories. Secrets and build/VCS directories are hidden.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult listFiles(
            @McpToolParam(required = false,
                    description = "Absolute path, or a path relative to the first allowed root. Omit to list the roots.")
            String path) {
        return observe("list_files", () -> files.listFiles(path));
    }

    @McpTool(name = "read_file",
            description = "Read a UTF-8 text file. Binary files and files over the size limit are refused.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult readFile(
            @McpToolParam(description = "Absolute path, or a path relative to the first allowed root.")
            String path) {
        return observe("read_file", () -> files.readFile(path));
    }

    @McpTool(name = "search_code",
            description = """
                    Find lines containing a literal text (case-insensitive, not a regex) in source files under the \
                    allowed roots. Returns file path, 1-based line number and the line. Check 'truncated' before \
                    concluding something is absent.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult searchCode(
            @McpToolParam(description = "Literal text to find, e.g. KafkaTemplate") String query,
            @McpToolParam(required = false, description = "Directory or file to search in. Omit to search all roots.")
            String path,
            @McpToolParam(required = false, description = "File-name glob filter, e.g. *.java or *.{yml,yaml}")
            String glob) {
        return observe("search_code", () -> files.searchCode(query, path, glob));
    }

    /** Runs a tool, logging name, outcome and latency (never arguments or content). */
    private CallToolResult observe(String tool, Supplier<?> call) {
        long start = System.nanoTime();
        MDC.put("toolName", tool);
        try {
            CallToolResult result = success(call.get());
            log.info("tool call succeeded durationMs={}", elapsedMs(start));
            return result;
        } catch (FileAccessException e) {
            log.info("tool call rejected reason={} durationMs={}", e.reason(), elapsedMs(start));
            return error(e.getMessage());
        } catch (RuntimeException e) {
            log.error("tool call failed durationMs={}", elapsedMs(start), e);
            return error(FileAccessException.Reason.INTERNAL_ERROR + ": unexpected server error");
        } finally {
            MDC.remove("toolName");
        }
    }

    private CallToolResult success(Object value) {
        return CallToolResult.builder().addTextContent(json.writeValueAsString(value)).isError(false).build();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
