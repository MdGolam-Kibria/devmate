package dev.devmate.client.folder;

import dev.devmate.client.agent.AgentProperties;
import dev.devmate.client.agent.DevMateAgent;
import dev.devmate.client.agent.FolderScope;
import dev.devmate.client.llm.LlmMessage;
import dev.devmate.client.mcp.McpClientService;
import dev.devmate.client.mcp.McpErrorCode;
import dev.devmate.client.mcp.McpToolResult;
import dev.devmate.client.mcp.McpUnavailableException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The folder side of folder Q&amp;A: suggest, open and approve folders through the files MCP server, and describe the
 * open folder to the {@link DevMateAgent}, which answers with the tools pinned to it (ADR-0005).
 *
 * <p>Only two tools are named here: {@code list_files} opens the folder, and {@code folder_overview}, when the server
 * has it, puts a short digest of the folder into the system prompt so even a model that calls no tools knows what
 * kind of project it is looking at.
 */
@Service
public class FolderAssistant {

    private static final String LIST_TOOL = "list_files";
    private static final String OVERVIEW_TOOL = "folder_overview";
    private static final int OVERVIEW_FILE_TYPES = 8;
    private static final int OVERVIEW_PROJECT_FILES = 15;
    private static final int OVERVIEW_RECENT_FILES = 5;

    private final McpClientService mcp;
    private final DevMateAgent agent;
    private final AgentProperties limits;
    private final JsonMapper json;

    public FolderAssistant(McpClientService mcp, DevMateAgent agent, AgentProperties limits, JsonMapper json) {
        this.mcp = mcp;
        this.agent = agent;
        this.limits = limits;
        this.json = json;
    }

    /**
     * The directories the files MCP server allows, as folder suggestions.
     *
     * @throws McpUnavailableException if the server can't be reached
     */
    public List<String> roots() {
        McpToolResult result = mcp.callTool(LIST_TOOL, Map.of());
        if (result.error()) {
            return List.of();
        }
        var roots = new ArrayList<String>();
        parse(result.text()).path("entries").forEach(entry -> roots.add(entry.path("path").asString()));
        return roots;
    }

    /** Whether the server refused a folder only because it's outside its allowed roots (the user may approve it). */
    public static boolean isOutsideAllowedRoots(FolderException e) {
        return e.getMessage().contains("outside the allowed roots");
    }

    /**
     * The user approved a folder outside the server's allowed roots: declare it as an MCP root, then open it.
     * Read-only access, the server's deny lists still apply, and the approval lasts until DevMate restarts.
     *
     * @throws FolderException         if the path is unusable, or the server didn't accept the approval
     * @throws McpUnavailableException if the server can't be reached
     */
    public FolderSnapshot allow(String rawPath) {
        String path = requireAbsolute(rawPath);
        Path folder = Path.of(path).normalize();
        mcp.approveRoot(folder);
        try {
            return open(path);
        } catch (FolderException e) {
            if (isOutsideAllowedRoots(e)) {
                mcp.revokeRoot(folder);
                throw new FolderException("ACCESS_DENIED: the files MCP server did not accept the approval. Start it "
                        + "with DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=true, and check that the folder exists");
            }
            throw e;
        }
    }

    /**
     * Checks a folder with the server (inside its sandbox, exists, is a directory) and lists it.
     *
     * @throws FolderException         if the path is unusable; the server's reason is passed through
     * @throws McpUnavailableException if the server can't be reached
     */
    public FolderSnapshot open(String rawPath) {
        String path = requireAbsolute(rawPath);
        McpToolResult result = mcp.callTool(LIST_TOOL, Map.of(FolderScope.PATH, path));
        if (result.error()) {
            throw new FolderException(result.text());
        }
        JsonNode listing = parse(result.text());
        var entries = new ArrayList<String>();
        listing.path("entries").forEach(entry -> entries.add(entry.path("name").asString()
                + ("DIRECTORY".equals(entry.path("type").asString()) ? "/" : "")));
        return new FolderSnapshot(listing.path("path").asString(path), entries, listing.path("truncated").asBoolean());
    }

    /**
     * Answers a question about the folder; the model may call the tools pinned to it.
     *
     * @param history earlier questions and answers of the conversation, oldest first
     * @throws FolderException         if the folder is no longer usable
     * @throws McpUnavailableException if the server can't be reached
     * @throws dev.devmate.client.llm.LlmException if the model fails
     */
    public DevMateAgent.Answer ask(String folder, String question, List<LlmMessage> history) {
        FolderSnapshot snapshot = open(folder);
        String overview = mcp.listTools().stream().anyMatch(tool -> tool.name().equals(OVERVIEW_TOOL))
                ? overview(snapshot.path())
                : null;
        return agent.ask(new DevMateAgent.Question(question, history,
                new DevMateAgent.Folder(snapshot.path(), context(snapshot, overview))));
    }

    /**
     * A few lines on what the folder holds (totals, file types, project files, recent changes), from the server's
     * {@code folder_overview}; null when the server refuses or answers in an unexpected shape.
     */
    String overview(String folder) {
        McpToolResult result = mcp.callTool(OVERVIEW_TOOL, Map.of(FolderScope.PATH, folder));
        if (result.error()) {
            return null;
        }
        JsonNode overview;
        try {
            overview = json.readTree(result.text());
        } catch (JacksonException e) {
            return null;
        }
        if (!overview.isObject()) {
            return null;
        }
        Path root = Path.of(folder);
        var text = new StringBuilder()
                .append(overview.path("files").asInt()).append(" files in ")
                .append(overview.path("directories").asInt()).append(" directories, ")
                .append(humanBytes(overview.path("totalBytes").asLong()));
        if (overview.path("truncated").asBoolean()) {
            text.append(" (scan limit reached, so these are lower bounds)");
        }
        text.append('\n');
        appendList(text, "File types", overview.path("fileTypes"), OVERVIEW_FILE_TYPES,
                type -> type.path("extension").asString() + " (" + type.path("files").asInt() + ")");
        appendList(text, "Project files", overview.path("projectFiles"), OVERVIEW_PROJECT_FILES,
                file -> relative(root, file.path("path").asString()) + " (" + file.path("kind").asString() + ")");
        appendList(text, "Recently modified", overview.path("recentlyModified"), OVERVIEW_RECENT_FILES,
                file -> relative(root, file.path("path").asString()));
        return text.toString();
    }

    private static void appendList(StringBuilder text, String label, JsonNode items, int max,
                                   Function<JsonNode, String> format) {
        if (!items.isArray() || items.isEmpty()) {
            return;
        }
        text.append(label).append(": ")
                .append(items.valueStream().limit(max).map(format).collect(Collectors.joining(", ")));
        if (items.size() > max) {
            text.append(", …");
        }
        text.append('\n');
    }

    private static String relative(Path root, String path) {
        try {
            return root.relativize(Path.of(path)).toString();
        } catch (IllegalArgumentException e) {   // includes InvalidPathException
            return path;
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        return kb < 1024 ? String.format(Locale.ROOT, "%.1f KB", kb) : String.format(Locale.ROOT, "%.1f MB", kb / 1024);
    }

    private static String requireAbsolute(String rawPath) {
        String path = rawPath == null ? "" : rawPath.strip();
        if (path.isEmpty()) {
            throw new FolderException("INVALID_ARGUMENT: enter the absolute path of a folder");
        }
        try {
            if (!Path.of(path).isAbsolute()) {
                throw new FolderException("INVALID_ARGUMENT: enter an absolute path, e.g. /home/me/project");
            }
        } catch (InvalidPathException e) {
            throw new FolderException("INVALID_ARGUMENT: not a valid path");
        }
        return path;
    }

    /** The folder's top-level entries and overview, for the agent's system prompt. */
    private String context(FolderSnapshot folder, String overview) {
        var context = new StringBuilder("\nTop-level entries of the folder:\n");
        folder.entries().stream().limit(limits.maxListedEntries()).forEach(e -> context.append("- ").append(e).append('\n'));
        if (folder.truncated() || folder.entries().size() > limits.maxListedEntries()) {
            context.append("- … (more entries not shown)\n");
        }
        if (overview != null) {
            context.append("\nFolder overview (secrets and build/VCS directories excluded):\n").append(overview);
        }
        return context.toString();
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            throw new McpUnavailableException(McpErrorCode.TRANSPORT_ERROR, "files",
                    "Files MCP server sent an unreadable result", e);
        }
    }
}
