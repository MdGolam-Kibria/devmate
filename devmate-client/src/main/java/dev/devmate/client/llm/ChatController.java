package dev.devmate.client.llm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plain chat with the configured model, to confirm a provider works. No tools or agent yet: M3 replaces this with
 * the agent endpoint that uses discovered MCP tools.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    public record ChatRequest(@NotBlank @Size(max = LlmRequest.MAX_USER_MESSAGE_LENGTH) String message) {}

    public record ChatReply(String provider, String model, String reply, Integer inputTokens, Integer outputTokens,
                            long durationMs) {}

    private final LlmClient llm;
    private final LlmProperties properties;

    public ChatController(LlmClient llm, LlmProperties properties) {
        this.llm = llm;
        this.properties = properties;
    }

    @PostMapping
    public ChatReply chat(@Valid @RequestBody ChatRequest request) {
        long start = System.nanoTime();
        LlmResponse response = llm.chat(LlmRequest.of(request.message()));
        return new ChatReply(properties.provider(), response.model(), response.text(), response.inputTokens(),
                response.outputTokens(), (System.nanoTime() - start) / 1_000_000);
    }

    @ExceptionHandler(LlmException.class)
    ResponseEntity<Map<String, String>> llmUnavailable(LlmException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "LLM_UNAVAILABLE", "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST",
                "message", "message must be 1-" + LlmRequest.MAX_USER_MESSAGE_LENGTH + " characters"));
    }
}
