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

## Current state (M1 + LLM, UI and folder Q&A slices)

```text
devmate/                       parent POM: Java 25, Boot 4.1.1, enforcer, test plugins
├── devmate-client/            Spring Boot (Web MVC on virtual threads) + Actuator
│   └── dev.devmate.client
│       ├── DevMateClientApplication
│       ├── llm/                  LlmClient port (+ LlmTool) → SpringAiLlmClient (runs the tool loop)
│       ├── mcp/McpToolClient     SDK client, Streamable HTTP to the files MCP server (lazy, reconnects)
│       ├── folder/               FolderAssistant (MCP tools → LlmTools), FolderScope (path pinned to folder)
│       ├── ui/                   Thymeleaf chat page, folder bar, session history + selected folder
│       └── web/RequestIdFilter   correlation id → response header + MDC
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

## Module plan

| Module              | Introduced | Depends on                         |
|---------------------|------------|------------------------------------|
| `devmate-client`    | M0         | — (MCP SDK client from M2, LLM from M3) |
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
