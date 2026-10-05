package dev.devmate.client.ui;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Prints the UI address once the app is ready and, for local desktop runs, opens it in the default browser.
 * Never blocks or fails startup. Skipped when {@code devmate.ui.open-browser=false} (tests, servers, containers) or
 * when there is no graphical session to open a browser in.
 */
@Component
class BrowserLauncher {

    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private final boolean openBrowser;

    BrowserLauncher(@Value("${devmate.ui.open-browser:false}") boolean openBrowser) {
        this.openBrowser = openBrowser;
    }

    @EventListener
    void onReady(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof WebServerApplicationContext web) || web.getWebServer() == null) {
            return; // non-web context (e.g. a test or CLI run)
        }
        URI ui = URI.create("http://localhost:" + web.getWebServer().getPort() + "/");
        log.info("DevMate UI ready at {}", ui);
        if (!openBrowser) {
            return;
        }
        command(System.getProperty("os.name", ""), System.getenv(), ui).ifPresentOrElse(
                command -> Thread.ofVirtual().name("browser-launcher").start(() -> launch(command, ui)),
                () -> log.info("No desktop session detected; open {} manually", ui));
    }

    /** The OS command that opens {@code uri} in the default browser, or empty when there's no desktop to use. */
    static Optional<List<String>> command(String osName, Map<String, String> env, URI uri) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return Optional.of(List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString()));
        }
        if (os.contains("mac")) {
            return Optional.of(List.of("open", uri.toString()));
        }
        boolean graphical = env.containsKey("DISPLAY") || env.containsKey("WAYLAND_DISPLAY");
        return graphical ? Optional.of(List.of("xdg-open", uri.toString())) : Optional.empty();
    }

    private static void launch(List<String> command, URI ui) {
        try {
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            log.info("Could not open a browser ({}); open {} manually", e.getMessage(), ui);
        }
    }
}
