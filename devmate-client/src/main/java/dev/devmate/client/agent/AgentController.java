package dev.devmate.client.agent;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON API of the agent (M3): ask a question, the model answers with the MCP tools it chooses. No folder scope: every
 * read-only tool of every reachable server is offered ({@code /api/folder/ask} pins tools to a folder). Stateless.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    public record AskRequest(@NotBlank @Size(max = LlmRequest.MAX_USER_MESSAGE_LENGTH) String question,
                             @Size(max = HistoryEntry.MAX_ENTRIES) List<@Valid HistoryEntry> history) {}

    public record AskReply(String provider, String model, String reply, List<String> toolsOffered,
                           List<String> unavailableServers, List<LlmToolCall> toolCalls, Integer inputTokens,
                           Integer outputTokens, long durationMs) {}

    private final DevMateAgent agent;
    private final LlmProperties properties;

    public AgentController(DevMateAgent agent, LlmProperties properties) {
        this.agent = agent;
        this.properties = properties;
    }

    @PostMapping(path = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AskReply ask(@Valid @RequestBody AskRequest request) {
        long start = System.nanoTime();
        DevMateAgent.Answer answer = agent.ask(DevMateAgent.Question.general(request.question().strip(),
                HistoryEntry.toMessages(request.history())));
        LlmResponse response = answer.response();
        return new AskReply(properties.provider(), response.model(), response.text(), answer.toolsOffered(),
                answer.unavailableServers(), response.toolCalls(), response.inputTokens(), response.outputTokens(),
                (System.nanoTime() - start) / 1_000_000);
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
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "message",
                "question (1-" + LlmRequest.MAX_USER_MESSAGE_LENGTH + " characters) is required; history: at most "
                        + HistoryEntry.MAX_ENTRIES + " entries of {role: user|assistant, text: 1-"
                        + LlmRequest.MAX_USER_MESSAGE_LENGTH + " characters}"));
    }
}
