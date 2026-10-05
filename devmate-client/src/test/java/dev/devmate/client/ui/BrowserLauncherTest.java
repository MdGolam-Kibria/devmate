package dev.devmate.client.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BrowserLauncherTest {

    private static final URI UI = URI.create("http://localhost:8083/");

    @Test
    void linuxDesktopUsesXdgOpen() {
        assertThat(BrowserLauncher.command("Linux", Map.of("DISPLAY", ":0"), UI))
                .contains(List.of("xdg-open", "http://localhost:8083/"));
        assertThat(BrowserLauncher.command("Linux", Map.of("WAYLAND_DISPLAY", "wayland-0"), UI)).isPresent();
    }

    @Test
    void headlessLinuxOpensNothing() {
        assertThat(BrowserLauncher.command("Linux", Map.of(), UI)).isEmpty();
    }

    @Test
    void macUsesOpen() {
        assertThat(BrowserLauncher.command("Mac OS X", Map.of(), UI)).contains(List.of("open", "http://localhost:8083/"));
    }

    @Test
    void windowsUsesUrlProtocolHandler() {
        assertThat(BrowserLauncher.command("Windows 11", Map.of(), UI))
                .contains(List.of("rundll32", "url.dll,FileProtocolHandler", "http://localhost:8083/"));
    }
}
