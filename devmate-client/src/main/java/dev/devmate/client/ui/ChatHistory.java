package dev.devmate.client.ui;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
     */
    public record Entry(Role role, String text, String html, String details, Instant at) implements Serializable {}

    private final List<Entry> entries = new ArrayList<>();

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public synchronized void add(Role role, String text, String html, String details) {
        entries.add(new Entry(role, text, html, details, Instant.now()));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    public synchronized void clear() {
        entries.clear();
    }
}
