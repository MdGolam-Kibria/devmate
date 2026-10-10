package dev.devmate.client.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The MCP servers DevMate connects to ({@code devmate.mcp.servers.<name>}). When two servers offer a tool with the
 * same name, the one with the lower {@code priority} wins (then the name, alphabetically): an explicit order, because
 * the order of map keys depends on which property source (yml, env, command line) set them. DevMate connects lazily,
 * so it starts even when a server isn't running; invalid settings stop startup.
 *
 * @param connectOnStartup try to connect and discover tools in the background once DevMate is up (logs the result)
 * @param servers          by name ({@code [a-z][a-z0-9-]*}, e.g. {@code files}), in priority order; may be empty
 */
@ConfigurationProperties("devmate.mcp")
public record McpClientProperties(@DefaultValue("true") boolean connectOnStartup, Map<String, Server> servers) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    public McpClientProperties {
        servers = servers == null ? Map.of() : servers;
        servers.forEach((name, server) -> {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("devmate.mcp.servers: '" + name
                        + "' is not a valid server name (lower case letters, digits and '-', starting with a letter)");
            }
            if (server == null || server.url() == null) {
                throw new IllegalArgumentException("devmate.mcp.servers." + name + ".url is required");
            }
        });
        var ordered = new LinkedHashMap<String, Server>();
        servers.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Server> e) -> e.getValue().priority())
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        servers = Collections.unmodifiableMap(ordered);
    }

    /**
     * One server, reached over Streamable HTTP.
     *
     * @param url                   endpoint, e.g. {@code http://127.0.0.1:8091/mcp}
     * @param priority              lower wins a tool-name clash with another server
     * @param connectTimeout        TCP connect timeout
     * @param initializationTimeout limit for the whole {@code initialize} handshake
     * @param requestTimeout        limit for one request ({@code tools/list}, {@code tools/call}); not retried
     * @param maxResponseSize       largest message accepted from the server; bigger ones are a transport error
     */
    public record Server(URI url,
                         @DefaultValue("100") int priority,
                         @DefaultValue("3s") Duration connectTimeout,
                         @DefaultValue("10s") Duration initializationTimeout,
                         @DefaultValue("30s") Duration requestTimeout,
                         @DefaultValue("4MB") DataSize maxResponseSize) {

        public Server {
            if (url != null) {
                String scheme = url.getScheme();
                if (!url.isAbsolute() || url.getHost() == null
                        || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                    throw new IllegalArgumentException("MCP server url must be an absolute http(s) URL: " + url);
                }
                if (url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
                    throw new IllegalArgumentException("MCP server url must not carry credentials, a query or a "
                            + "fragment: " + url.getScheme() + "://" + url.getHost());
                }
            }
            requirePositive("connect-timeout", connectTimeout);
            requirePositive("initialization-timeout", initializationTimeout);
            requirePositive("request-timeout", requestTimeout);
            if (maxResponseSize == null || maxResponseSize.toBytes() < 1024
                    || maxResponseSize.toBytes() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("max-response-size must be between 1KB and 2GB");
            }
        }

        private static void requirePositive(String name, Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
