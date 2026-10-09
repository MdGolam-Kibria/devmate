package dev.devmate.files.tools;

import dev.devmate.files.access.ClientRoots;
import dev.devmate.files.access.FileAccessException;
import dev.devmate.files.service.FileService;
import dev.devmate.files.service.FolderInsights;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP adapter: declares the tools (input schemas are generated from these signatures) and delegates to
 * {@link FileService}. Results are built here rather than by the framework so the wire format is ours: success is
 * one JSON text block; failure is {@code isError=true} with a single {@code REASON: detail} line (see
 * {@link FileAccessException}). Unexpected failures never leak their message.
 *
 * <p>Each call runs under {@link ClientRoots#policyFor}: the configured roots, plus the client's roots if enabled.
 *
 * <p>Every call is logged with its outcome, latency and the result's counts and flags (e.g. {@code matches=12
 * truncated=false}; never arguments, paths or content), and timed as {@value #METRIC}, tagged {@code tool},
 * {@code outcome} ({@code success}, {@code rejected}, {@code error}) and {@code reason}.
 */
@Component
public class FileTools {

    static final String METRIC = "devmate.mcp.tool.calls";

    private static final Logger log = LoggerFactory.getLogger(FileTools.class);

    private final FileService files;
    private final FolderInsights insights;
    private final ClientRoots clientRoots;
    private final JsonMapper json;
    private final MeterRegistry meters;

    public FileTools(FileService files, FolderInsights insights, ClientRoots clientRoots, JsonMapper json,
                     MeterRegistry meters) {
        this.files = files;
        this.insights = insights;
        this.clientRoots = clientRoots;
        this.json = json;
        this.meters = meters;
    }

    @McpTool(name = "list_files",
            description = """
                    List the entries of one directory (directories first) with type, size in bytes and last-modified \
                    time. With no path, lists the allowed root directories. Secrets and build/VCS directories are \
                    hidden. For several levels at once use folder_tree.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult listFiles(
            McpSyncRequestContext context,
            @McpToolParam(required = false,
                    description = "Absolute path, or a path relative to the first allowed root. Omit to list the roots.")
            String path) {
        return observe("list_files", () -> files.listFiles(path, clientRoots.policyFor(context)));
    }

    @McpTool(name = "read_file",
            description = """
                    Read a UTF-8 text file, whole or a line range (startLine/endLine, 1-based, inclusive). Returns \
                    lineCount (lines in the whole file), the startLine/endLine returned and 'truncated' (content \
                    limit reached: continue from endLine + 1). Use a range for large files. Binary files are refused.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult readFile(
            McpSyncRequestContext context,
            @McpToolParam(description = "Absolute path, or a path relative to the first allowed root.")
            String path,
            @McpToolParam(required = false, description = "First line to return (1-based). Omit for line 1.")
            Integer startLine,
            @McpToolParam(required = false, description = "Last line to return (inclusive). Omit for the end.")
            Integer endLine) {
        return observe("read_file", () -> files.readFile(path, startLine, endLine, clientRoots.policyFor(context)));
    }

    @McpTool(name = "search_code",
            description = """
                    Find lines containing a literal text (case-insensitive, not a regex) in source files under the \
                    allowed roots. Returns file path, 1-based line number and the line, plus 'before'/'after' \
                    context lines when contextLines is set; also filesMatched and filesScanned. Check 'truncated' \
                    before concluding something is absent.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult searchCode(
            McpSyncRequestContext context,
            @McpToolParam(description = "Literal text to find, e.g. KafkaTemplate") String query,
            @McpToolParam(required = false, description = "Directory or file to search in. Omit to search all roots.")
            String path,
            @McpToolParam(required = false, description = "File-name glob filter, e.g. *.java or *.{yml,yaml}")
            String glob,
            @McpToolParam(required = false, description = "Lines of context before and after each match (0-5).")
            Integer contextLines) {
        return observe("search_code",
                () -> files.searchCode(query, path, glob, contextLines, clientRoots.policyFor(context)));
    }

    @McpTool(name = "folder_tree",
            description = """
                    Show the directory tree below a folder as indented text (two spaces per level; directories end \
                    in '/', a directory not expanded because of the depth ends in '/…', a symbolic link ends in \
                    '@'). Secrets and build/VCS directories are hidden. Good first step to see a project's layout.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult folderTree(
            McpSyncRequestContext context,
            @McpToolParam(required = false, description = "Directory to start at. Omit for the allowed roots.")
            String path,
            @McpToolParam(required = false, description = "Levels to expand (default 3, at most 8).")
            Integer depth) {
        return observe("folder_tree", () -> insights.tree(path, depth, clientRoots.policyFor(context)));
    }

    @McpTool(name = "find_files",
            description = """
                    Find files by name glob, e.g. *Controller.java, pom.xml or *.{yml,yaml}; a glob containing '/' \
                    matches the path below the start directory instead, e.g. src/main/**/*.java. Returns path, size \
                    and last-modified time, sorted by path. Check 'truncated' before concluding a file is absent.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult findFiles(
            McpSyncRequestContext context,
            @McpToolParam(description = "File-name glob, e.g. *Service.java") String glob,
            @McpToolParam(required = false, description = "Directory to search in. Omit to search all roots.")
            String path) {
        return observe("find_files", () -> insights.findFiles(glob, path, clientRoots.policyFor(context)));
    }

    @McpTool(name = "folder_overview",
            description = """
                    Summarise a folder: file and directory counts, total size, the most common file types, the \
                    files that show what kind of project it is (build, Docker, CI, README, config), and the largest \
                    and most recently modified files. Reads no file contents.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult folderOverview(
            McpSyncRequestContext context,
            @McpToolParam(required = false, description = "Directory to summarise. Omit for all roots.")
            String path) {
        return observe("folder_overview", () -> insights.overview(path, clientRoots.policyFor(context)));
    }

    /** Runs a tool, logging name, outcome, result stats and latency (never arguments or content), and times it. */
    private CallToolResult observe(String tool, Supplier<?> call) {
        long start = System.nanoTime();
        MDC.put("toolName", tool);
        try {
            JsonNode value = json.valueToTree(call.get());
            log.info("tool call succeeded {} durationMs={}", stats(value), elapsedMs(start));
            record(tool, "success", "none", start);
            return CallToolResult.builder().addTextContent(json.writeValueAsString(value)).isError(false).build();
        } catch (FileAccessException e) {
            log.info("tool call rejected reason={} durationMs={}", e.reason(), elapsedMs(start));
            record(tool, "rejected", e.reason().name(), start);
            return error(e.getMessage());
        } catch (RuntimeException e) {
            log.error("tool call failed durationMs={}", elapsedMs(start), e);
            record(tool, "error", FileAccessException.Reason.INTERNAL_ERROR.name(), start);
            return error(FileAccessException.Reason.INTERNAL_ERROR + ": unexpected server error");
        } finally {
            MDC.remove("toolName");
        }
    }

    /**
     * The result's top-level counts and flags: arrays as their size, numbers and booleans as they are. Strings (paths,
     * content, the query) are left out on purpose.
     */
    static String stats(JsonNode result) {
        var stats = new StringJoiner(" ");
        result.properties().forEach(field -> {
            JsonNode value = field.getValue();
            if (value.isArray()) {
                stats.add(field.getKey() + "=" + value.size());
            } else if (value.isNumber() || value.isBoolean()) {
                stats.add(field.getKey() + "=" + value);
            }
        });
        return stats.toString();
    }

    private void record(String tool, String outcome, String reason, long startNanos) {
        Timer.builder(METRIC)
                .description("Files MCP tool calls")
                .tags("tool", tool, "outcome", outcome, "reason", reason)
                .register(meters)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
