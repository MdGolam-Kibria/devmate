package dev.devmate.client.ui;

import java.io.Serial;
import java.io.Serializable;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/** The folder one browser session is asking about, or none (plain chat). Set only after the server accepted it. */
@Component
@SessionScope
public class SelectedFolder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String path;
    private int entryCount;

    public synchronized String path() {
        return path;
    }

    public synchronized int entryCount() {
        return entryCount;
    }

    public synchronized void select(String path, int entryCount) {
        this.path = path;
        this.entryCount = entryCount;
    }

    public synchronized void clear() {
        path = null;
        entryCount = 0;
    }
}
