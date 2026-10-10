package dev.devmate.client.mcp;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.net.URI;
import org.springframework.stereotype.Component;

/**
 * Builds the SDK transport for a configured server: Streamable HTTP (MCP 2025-03-26+), the only transport DevMate's
 * servers speak. Every connection attempt gets a new transport, so a broken one is never reused.
 */
@Component
public class McpTransport {

    /** A fresh, unconnected transport to {@code server.url()}, with its connect timeout and response size cap. */
    public McpClientTransport create(McpClientProperties.Server server) {
        URI url = server.url();
        String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/mcp" : url.getRawPath();
        return HttpClientStreamableHttpTransport.builder(url.getScheme() + "://" + url.getRawAuthority())
                .endpoint(path)
                .connectTimeout(server.connectTimeout())
                .maxResponseSize((int) server.maxResponseSize().toBytes())
                .build();
    }
}
