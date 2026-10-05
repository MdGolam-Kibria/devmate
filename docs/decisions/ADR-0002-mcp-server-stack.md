# ADR-0002 — MCP server stack and transport

- Status: Accepted
- Date: 2026-10-05

## Context

M1 needs DevMate's first MCP server (`devmate-files-mcp`). Later servers (Git, DB, Kafka, …) follow the same
pattern, so this choice sets the template. Verified options (Maven Central, 2026-10-05):

- **MCP Java SDK** `io.modelcontextprotocol.sdk` 2.0.1. This is the official SDK, with the protocol types
  (`McpSchema`), the server/client sessions and the transports.
- **Spring AI** 2.0.1, built against Boot 4.1.1. It has `spring-ai-starter-mcp-server-webmvc` (auto-configured SDK
  server + WebMVC transport) and `@McpTool`/`@McpToolParam` annotations that generate tool input schemas. It ships
  SDK 2.0.0.

The MCP spec defines stdio and Streamable HTTP transports. HTTP+SSE is the deprecated 2024-11-05 transport.

## Decision

- Build MCP servers on the **Spring AI MCP server starter (WebMVC)** over the **official MCP Java SDK**. Pin the SDK
  to the latest stable patch (2.0.1) by importing `mcp-bom` before `spring-ai-bom` in the root POM.
- Transport: **Streamable HTTP** (`spring.ai.mcp.server.protocol=streamable`) at `/mcp`, sync API, on virtual
  threads. Each server is its own process with its own port.
- Declare tools with `@McpTool` on a thin adapter class. The adapter returns `CallToolResult` itself: success is a
  single JSON text block, failure is `isError=true` with one `REASON: detail` line. Business logic lives in plain
  services with no MCP types.
- Expose only the `tools` capability (resources, prompts and completion are disabled) and set
  `readOnlyHint`/`destructiveHint`/`idempotentHint` honestly on every tool.
- Bind to loopback by default and reject a disallowed `Origin` with 403 (the spec requires DNS-rebinding protection).

## Alternatives

- **Raw SDK** (`McpServer.sync(...)` + hand-built `SyncToolSpecification`s and JSON schemas). No dependency on
  Spring AI and full control, but more boilerplate for transports and schemas. Kept as a fallback, since our
  services don't depend on Spring AI.
- **stdio transport.** Simplest for desktop hosts, but it ties each server's life to the client process and doesn't
  fit later remote servers (K8s, Kafka). It can be added per server with `spring.ai.mcp.server.stdio=true` if needed.
- **Stateless Streamable HTTP.** Scales horizontally more easily, but it has no sessions and no server→client
  notifications, and M2 needs to exercise the full session lifecycle.
- **Let the framework turn exceptions into tool errors.** Rejected: Spring AI 2.0.1 appends the root-cause message,
  so cause-less errors were sent twice, and we would not control the error contract.

## Consequences

- Tool input schemas come from Java signatures. Renaming a parameter is a breaking change for clients.
- Overriding the SDK to 2.0.1 under Spring AI 2.0.1 (which ships 2.0.0) is a patch-level override. The end-to-end
  IT, which uses the official SDK client, guards it.
- No authentication yet. Loopback binding plus the Origin check is the boundary until M13 (RBAC/approval).
- Tool results are JSON text, not `structuredContent`/`outputSchema`. They can be added later without breaking
  clients.
