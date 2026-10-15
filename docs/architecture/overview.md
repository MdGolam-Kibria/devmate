# Architecture overview

## Target architecture

```text
User ──► DevMate Agent ──► LLM (tool selection)
              │
              ▼
         MCP Client ── connection · session · discovery · registry · execution · errors
              │
     ┌────────┼─────────┐
     ▼        ▼         ▼
 Files MCP  Git MCP  Code MCP      (later: DB, Kafka, OTel, Redis, K8s)
```

MCP is the protocol boundary between the agent and every external system. The client finds tools at runtime
(`initialize` → `tools/list`) and never hardcodes them. The LLM receives the discovered tool definitions.

## Current state (M3: agent over the MCP client)

```text
devmate/                       parent POM: Java 25, Boot 4.1.1, enforcer, test plugins
├── devmate-client/            Spring Boot (Web MVC on virtual threads) + Actuator
│   └── dev.devmate.client
│       ├── DevMateClientApplication
│       ├── agent/                DevMateAgent (ADR-0005): discovered read-only tools → LlmTools (McpToolBridge),
│       │                         FolderScope, history trimming, system prompt; AgentController = /api/agent/ask
│       ├── llm/                  LlmClient port (+ LlmTool, LlmMessage) → SpringAiLlmClient (runs the tool loop)
│       ├── mcp/                  MCP client (ADR-0004): McpClientService facade → ToolRegistry + ToolExecutor →
│       │                         McpConnection per server → McpSession → McpTransport (SDK, Streamable HTTP);
│       │                         McpController = /api/mcp (servers, tools, call; no LLM)
│       ├── folder/               FolderAssistant (roots, open, approve, overview → folder context for the agent)
│       ├── ui/                   Thymeleaf chat page, folder bar, session history + selected folder
│       └── web/                  RequestIdFilter (correlation id → header + MDC), HostHeaderFilter (DNS rebinding)
└── devmate-files-mcp/         MCP server, Streamable HTTP :8091/mcp (ADR-0002)
    └── dev.devmate.files
        ├── tools/FileTools          @McpTool adapter → CallToolResult (wire format owned here)
        ├── service/FileService      list / read / search, no MCP types
        ├── access/FileAccessPolicy  single sandbox gate (real-path containment, deny lists)
        ├── config/FilesProperties   validated limits; no roots → no start
        └── web/OriginValidationFilter  DNS-rebinding protection
```

The template for every MCP server module (M1 sets it): adapter (MCP types only) → service (domain logic) →
policy (security gate) → validated properties. Tools are read-only unless a later milestone adds the M13 approval flow.

Runtime properties:

- **Virtual threads** (`spring.threads.virtual.enabled`). Request handling, and later the blocking MCP/LLM calls,
  runs on virtual threads, so the code stays simple and blocking with no reactive stack.
- **Secure by default.** Only `health` and `info` actuator endpoints are exposed. Health details are hidden
  (except in the `dev` profile). Error responses never include messages or stack traces.
- **Graceful shutdown** with a configurable grace period. Liveness and readiness probes are ready for Kubernetes.
- **Logging.** SLF4J + Logback. Every line carries a `[app,requestId]` correlation prefix. The `json-logs` profile
  switches to ECS JSON, where MDC keys become fields. Later milestones add `traceId`, `spanId`, `sessionId` and `toolName`
  through MDC in the same way.

MCP client flow (M2): `initialize` → `tools/list` (all servers, cached per session / `list_changed`) → registry
(unique names, lower `priority` wins a clash) → `tools/call` after the read-only policy and a schema check of the
arguments → `McpToolResult` or, when a server can't be used, `McpUnavailableException` (error codes in ADR-0004).

Agent flow (M3): question (+ history) → `DevMateAgent` → `McpClientService.tools()` → read-only tools (in a folder:
only those with a `path`, pinned by `FolderScope`) → `LlmTool`s → `LlmClient` (model ⇄ tool calls through
`ToolExecutor`, at most `max-tool-rounds`) → answer + tools used. Unreachable servers drop out; the question is still
answered.

## Module plan

| Module              | Introduced | Depends on                         |
|---------------------|------------|------------------------------------|
| `devmate-client`    | M0         | MCP SDK client (M2), Spring AI (LLM, M3) |
| `devmate-files-mcp` | M1         | Spring AI MCP server starter → MCP SDK 2.0.1 |
| `devmate-git-mcp`   | M4         | same stack as files (ADR-0002)     |

MCP server modules never depend on `devmate-client`, and the client never depends on a server module.
They talk only over MCP.

## Conventions

- Package root `dev.devmate.<module>`. Group packages by feature, not by layer, once features exist.
- MCP servers bind to loopback by default, expose only the `tools` capability, and return errors as
  `isError=true` with `REASON: detail`. They log tool name, outcome and latency, never arguments or content.
- Tests: `*Test` are unit tests (surefire). `*IT` boot a Spring context or touch external systems (failsafe).
  Both run in `mvn verify`.
- Versions live only in the root POM (Boot parent + root `<properties>`/`<dependencyManagement>`).
- No credentials or environment-specific values in source. Use environment variables or profile files.
