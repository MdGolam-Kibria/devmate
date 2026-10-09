package dev.devmate.client.ui;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/**
 * The conversation shown to one browser session, newest last. Display only: each question is still sent to the model
 * on its own (multi-turn context arrives with the M3 agent). Capped so a long session can't grow without bound.
 */
@Component
@SessionScope
public class ChatHistory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    static final int MAX_ENTRIES = 100;

    public enum Role {
        USER,
        ASSISTANT,
        ERROR
    }

    /**
     * @param html    sanitised HTML rendering of {@code text} (assistant replies only), else null
     * @param details short metadata line (model, tokens, latency) or null
     * @param tools   tool calls made for this reply, in order; empty if none
     */
    public record Entry(Role role, String text, String html, String details, List<ToolUse> tools, Instant at)
            implements Serializable {

        public Entry {
            tools = List.copyOf(Objects.requireNonNullElse(tools, List.of()));
        }
    }

    /**
     * One tool call as shown under "Tools used".
     *
     * @param call    {@code name {arguments}}
     * @param outcome one line on the result (counts and flags, or the error reason); never the tool's output
     */
    public record ToolUse(String call, String outcome, boolean error, long durationMs) implements Serializable {}

    private final List<Entry> entries = new ArrayList<>();

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public void add(Role role, String text, String html, String details) {
        add(role, text, html, details, List.of());
    }

    public synchronized void add(Role role, String text, String html, String details, List<ToolUse> tools) {
        entries.add(new Entry(role, text, html, details, tools, Instant.now()));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    public synchronized void clear() {
        entries.clear();
    }
}
