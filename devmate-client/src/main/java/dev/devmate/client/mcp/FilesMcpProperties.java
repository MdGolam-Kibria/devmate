package dev.devmate.client.mcp;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the files MCP server listens (env {@code DEVMATE_FILES_MCP_URL}). DevMate connects lazily, so it starts even
 * when the server isn't running; the folder feature reports it as unavailable until it is.
 *
 * @param url            Streamable HTTP endpoint, e.g. {@code http://127.0.0.1:8091/mcp}
 * @param connectTimeout TCP connect timeout
 * @param requestTimeout timeout of one MCP request (initialize, tools/list, tools/call)
 */
@Validated
@ConfigurationProperties("devmate.mcp.files")
public record FilesMcpProperties(
        @NotNull URI url,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("30s") Duration requestTimeout) {}
