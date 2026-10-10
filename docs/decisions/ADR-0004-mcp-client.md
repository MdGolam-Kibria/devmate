# ADR-0004 — MCP client: components, multi-server registry, failure model

- Status: Accepted (M2)
- Date: 2026-10-10

## Context

M2 asks for DevMate's core MCP client: connection, initialization, dynamic tool discovery, a tool registry, tool
execution and handling of connection, timeout, invalid-tool, invalid-argument, server, session and transport errors,
proven with `search_code("Kafka")` and no LLM. Before M2 a single `McpToolClient` class did connect/list/call for the
files server only, with one undifferentiated failure type. M4+ add more servers (Git, Code, DB, …).

The official MCP Java SDK 2.0.1 (ADR-0002) already provides, and was checked for: Streamable HTTP client transport with
a per-message size cap (`maxResponseSize`), `initialize` with protocol negotiation, automatic re-initialization when the
server reports an unknown session, `tools/list` across all pages, `notifications/tools/list_changed` callbacks, JSON-RPC
errors as `McpError`, and a JSON Schema validator (`McpJsonDefaults.getSchemaValidator()`, the one SDK servers use).

## Decision

1. **Six components over the SDK, no protocol of our own** (package `dev.devmate.client.mcp`):
   `McpTransport` (config → SDK Streamable HTTP transport), `McpSession` (one initialized SDK session; the only place
   besides `McpConnection` that sees SDK types), `McpConnection` (one per configured server: lazy open, drop on
   breakage, failure classification, retry), `ToolRegistry` (tools of all servers), `ToolExecutor` (one
   `tools/call`: lookup, policy, schema check, mapping, logging, metric) and `McpClientService` (the facade used by
   folder Q&A, the `/api/mcp` API and, from M3, the agent).
2. **Several servers by configuration**: `devmate.mcp.servers.<name>.url` (+ timeouts, size cap). Tool names are
   unique across servers; on a clash the lower `priority` wins (then the name). Explicit, because the order of map
   keys depends on which property source set them: found by the acceptance check, where a server added on the
   command line silently shadowed the yml's `files` server.
3. **Registry cache keyed by a per-connection version** that changes on every new session and on `list_changed`;
   a server saying a listed tool is unknown invalidates its list. Lookups go server by server in priority order and
   stop at the first match. An unknown name while some server is unreachable is an outage, not `UNKNOWN_TOOL`.
4. **Failure model** (`McpErrorCode`): outages are thrown as `McpUnavailableException`
   (`CONNECTION_FAILED`, `TIMEOUT`, `SESSION_ERROR`, `TRANSPORT_ERROR`); one call's failure is returned as a
   `McpToolResult` the model can react to (`UNKNOWN_TOOL`, `INVALID_ARGUMENTS`, `TOOL_NOT_ALLOWED`, `TOOL_ERROR`,
   `SERVER_ERROR`, and `TIMEOUT` for a slow `tools/call` on a live session). Messages are safe to show: no server
   output beyond one cleaned, bounded line of a JSON-RPC error message.
5. **Retry once, only when it is safe and can help**: after a broken connection/session/transport, read-only requests
   are re-sent on a new session. Timeouts and oversized answers are never retried (they would repeat).
6. **Policy before the network**: only tools the server declares `readOnlyHint=true` are executed (human approval
   for others is M13), and arguments are validated against the tool's input schema with the SDK validator, so bad
   calls never reach a server. A schema the validator can't use is left to the server.
7. **A no-LLM API** `GET /api/mcp/servers`, `GET /api/mcp/tools[?refresh=true]`,
   `POST /api/mcp/tools/{name}/call` (JSON only). Since it returns raw tool output, DevMate now refuses requests whose
   `Host` isn't allowed (`devmate.http.allowed-hosts`, default loopback names): DNS-rebinding protection.

## Alternatives

- **Spring AI MCP client starter** (`spring-ai-starter-mcp-client`): auto-configures SDK clients and exposes tools as
  Spring AI `ToolCallback`s. Rejected for now: it couples discovery to Spring AI's tool abstraction (the agent must
  depend on DevMate's `LlmClient` port, ADR-0003), connects eagerly at startup, and offers none of the failure
  classification, policy or roots handling DevMate needs.
- **Keep one `McpToolClient`**: simplest, but every new server and failure rule would grow one class; the brief names
  the components explicitly.
- **First-configured-wins clash rule**: rejected (decision 2).
- **Retry every failure**: doubles the wait on timeouts and oversized answers, and would repeat non-idempotent calls.

## Consequences

- Adding a server is configuration; its tools appear in folder Q&A (if read-only with a `path` parameter), the API
  and later the agent, with no code change.
- Non-read-only tools are unusable until M13 adds approval.
- Only Streamable HTTP is supported; a stdio transport would be a new branch in `McpTransport`.
- Lists are cached; a server that changes its tools without `list_changed` is noticed at the next new session, a
  failed call (`Unknown tool`) or `?refresh=true`.
- `Host` filtering means a DevMate bound to another address needs its host name in `DEVMATE_ALLOWED_HOSTS`.
