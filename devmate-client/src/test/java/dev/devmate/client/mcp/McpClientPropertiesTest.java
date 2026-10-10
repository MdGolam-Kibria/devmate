package dev.devmate.client.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

class McpClientPropertiesTest {

    @Test
    void bindsServersInOrderWithSecureDefaults() {
        McpClientProperties properties = bind(Map.of(
                "devmate.mcp.servers.files.url", "http://127.0.0.1:8091/mcp",
                "devmate.mcp.servers.git.url", "http://127.0.0.1:8092/mcp",
                "devmate.mcp.servers.git.request-timeout", "5s"));

        assertThat(properties.connectOnStartup()).isTrue();
        McpClientProperties.Server files = properties.servers().get("files");
        assertThat(files.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(files.initializationTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(files.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(files.maxResponseSize()).isEqualTo(DataSize.ofMegabytes(4));
        assertThat(properties.servers().get("git").requestTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void serversAreOrderedByPriorityThenNameWhateverTheBindingOrder() {
        var bound = new LinkedHashMap<String, McpClientProperties.Server>();
        bound.put("zeta", server("http://h/z"));
        bound.put("files", new McpClientProperties.Server(URI.create("http://h/f"), 10, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(1)));
        bound.put("alpha", server("http://h/a"));

        assertThat(new McpClientProperties(true, bound).servers().keySet()).containsExactly("files", "alpha", "zeta");
        assertThat(bind(Map.of("devmate.mcp.servers.files.url", "http://h/f")).servers().get("files").priority())
                .isEqualTo(100);
    }

    @Test
    void noServersIsAllowed() {
        assertThat(bind(Map.of("devmate.mcp.connect-on-startup", "false")).servers()).isEmpty();
    }

    @Test
    void rejectsUnusableSettings() {
        assertThatThrownBy(() -> new McpClientProperties(true, Map.of("Files!", server("http://h/mcp"))))
                .hasMessageContaining("not a valid server name");
        assertThatThrownBy(() -> server("file:///etc/passwd")).hasMessageContaining("absolute http(s) URL");
        assertThatThrownBy(() -> server("/mcp")).hasMessageContaining("absolute http(s) URL");
        assertThatThrownBy(() -> server("http://user:secret@h/mcp"))
                .hasMessageContaining("must not carry credentials")
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> new McpClientProperties.Server(URI.create("http://h/mcp"), 1, Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(1)))
                .hasMessageContaining("connect-timeout");
        assertThatThrownBy(() -> new McpClientProperties.Server(URI.create("http://h/mcp"), 1, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofBytes(10)))
                .hasMessageContaining("max-response-size");
        assertThatThrownBy(() -> bind(Map.of("devmate.mcp.servers.files.request-timeout", "5s")))
                .hasRootCauseMessage("devmate.mcp.servers.files.url is required");
    }

    private static McpClientProperties.Server server(String url) {
        return new McpClientProperties.Server(URI.create(url), 100, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), DataSize.ofMegabytes(1));
    }

    private static McpClientProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("devmate.mcp", McpClientProperties.class);
    }
}
