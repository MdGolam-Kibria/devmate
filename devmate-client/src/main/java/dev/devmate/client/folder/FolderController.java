package dev.devmate.client.folder;

import dev.devmate.client.agent.DevMateAgent;
import dev.devmate.client.agent.HistoryEntry;
import dev.devmate.client.llm.LlmException;
import dev.devmate.client.llm.LlmProperties;
import dev.devmate.client.llm.LlmRequest;
import dev.devmate.client.llm.LlmResponse;
import dev.devmate.client.llm.LlmToolCall;
import dev.devmate.client.mcp.McpUnavailableException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON API for asking about a folder (scripts, curl). Stateless: every request names its folder and may carry the
 * conversation so far ({@code history}).
 */
@RestController
@RequestMapping("/api/folder")
public class FolderController {

    public record AskRequest(@NotBlank @Size(max = 4096) String folder,
                             @NotBlank @Size(max = LlmRequest.MAX_USER_MESSAGE_LENGTH) String question,
                             @Size(max = HistoryEntry.MAX_ENTRIES) List<@Valid HistoryEntry> history) {}

    public record AskReply(String folder, String provider, String model, String reply, List<String> toolsOffered,
                           List<LlmToolCall> toolCalls, Integer inputTokens, Integer outputTokens, long durationMs) {}

    private final FolderAssistant folders;
    private final LlmProperties properties;

    public FolderController(FolderAssistant folders, LlmProperties properties) {
        this.folders = folders;
        this.properties = properties;
    }

    /** Folders the files MCP server allows. */
    @GetMapping("/roots")
    public Map<String, List<String>> roots() {
        return Map.of("roots", folders.roots());
    }

    @PostMapping("/ask")
    public AskReply ask(@Valid @RequestBody AskRequest request) {
        long start = System.nanoTime();
        DevMateAgent.Answer answer = folders.ask(request.folder().strip(), request.question().strip(),
                HistoryEntry.toMessages(request.history()));
        LlmResponse response = answer.response();
        return new AskReply(request.folder().strip(), properties.provider(), response.model(), response.text(),
                answer.toolsOffered(), response.toolCalls(), response.inputTokens(), response.outputTokens(),
                (System.nanoTime() - start) / 1_000_000);
    }

    @ExceptionHandler(FolderException.class)
    ResponseEntity<Map<String, String>> folderRejected(FolderException e) {
        HttpStatus status = switch (e.reason()) {
            case "ACCESS_DENIED" -> HttpStatus.FORBIDDEN;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.reason(), "message", e.getMessage()));
    }

    @ExceptionHandler(McpUnavailableException.class)
    ResponseEntity<Map<String, String>> mcpUnavailable(McpUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "MCP_UNAVAILABLE", "message", e.getMessage()));
    }

    @ExceptionHandler(LlmException.class)
    ResponseEntity<Map<String, String>> llmUnavailable(LlmException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "LLM_UNAVAILABLE", "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST",
                "message", "folder (1-4096) and question (1-" + LlmRequest.MAX_USER_MESSAGE_LENGTH
                        + " characters) are required; history: at most " + HistoryEntry.MAX_ENTRIES
                        + " entries of {role: user|assistant, text}"));
    }
}
