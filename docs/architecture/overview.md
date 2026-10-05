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

## Current state (M0)

```text
devmate/                       parent POM: Java 25, Boot 4.1.1, enforcer, test plugins
└── devmate-client/            Spring Boot (Web MVC on virtual threads) + Actuator
    └── dev.devmate.client
        ├── DevMateClientApplication
        └── web/RequestIdFilter   correlation id → response header + MDC
```

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
| `devmate-files-mcp` | M1         | MCP Java SDK server                |
| `devmate-git-mcp`   | M4         | MCP Java SDK server                |

MCP server modules never depend on `devmate-client`, and the client never depends on a server module.
They talk only over MCP.

## Conventions

- Package root `dev.devmate.<module>`. Group packages by feature, not by layer, once features exist.
- Tests: `*Test` are unit tests (surefire). `*IT` boot a Spring context or touch external systems (failsafe).
  Both run in `mvn verify`.
- Versions live only in the root POM (Boot parent + root `<properties>`/`<dependencyManagement>`).
- No credentials or environment-specific values in source. Use environment variables or profile files.
