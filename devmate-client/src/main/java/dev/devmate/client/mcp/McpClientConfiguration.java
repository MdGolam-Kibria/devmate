package dev.devmate.client.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds one {@link McpConnection} per configured server and the registry and executor on top of them. */
@Configuration(proxyBeanMethods = false)
class McpClientConfiguration {

    @Bean
    McpRoots mcpRoots() {
        return new McpRoots();
    }

    /** Closed on shutdown, which closes every session. */
    @Bean
    ToolRegistry toolRegistry(McpClientProperties properties, McpTransport transport, McpRoots roots,
                              ObjectProvider<BuildProperties> build) {
        BuildProperties info = build.getIfAvailable();
        var clientInfo = McpSchema.Implementation.builder("devmate-client", info == null ? "dev" : info.getVersion())
                .build();
        return new ToolRegistry(properties.servers().entrySet().stream()
                .map(server -> new McpConnection(server.getKey(), server.getValue(), transport, roots, clientInfo))
                .toList());
    }

    /** Arguments are checked with the SDK's own JSON Schema validator, the one MCP servers use too. */
    @Bean
    ToolExecutor toolExecutor(ToolRegistry registry, MeterRegistry meters) {
        return new ToolExecutor(registry, McpJsonDefaults.getSchemaValidator(), meters);
    }
}
