package dev.devmate.client.agent;

import dev.devmate.client.llm.LlmMessage;
import dev.devmate.client.llm.LlmRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;

/**
 * One earlier message, as the JSON APIs accept it: {@code {"role": "user" | "assistant", "text": "…"}}. The APIs are
 * stateless: a caller that wants a conversation sends the history with each question.
 */
public record HistoryEntry(@NotBlank @Pattern(regexp = "user|assistant") String role,
                           @NotBlank @Size(max = LlmRequest.MAX_USER_MESSAGE_LENGTH) String text) {

    /** Most entries one request may carry; the agent keeps fewer (devmate.agent.max-history-messages). */
    public static final int MAX_ENTRIES = 50;

    public static List<LlmMessage> toMessages(List<HistoryEntry> history) {
        return history == null ? List.of() : history.stream()
                .map(entry -> new LlmMessage(LlmMessage.Role.valueOf(entry.role().toUpperCase(Locale.ROOT)),
                        entry.text()))
                .toList();
    }
}
