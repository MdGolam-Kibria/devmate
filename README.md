# DevMate

A personal AI developer assistant: an **MCP client + AI agent** that collects evidence from developer
systems (files, Git, code, and later DB, Kafka, OTel, Redis, Kubernetes) through MCP tools, then uses an LLM to analyse it.

> Status: **M1 — Files MCP Server** is complete. No MCP client or LLM yet. See [`docs/milestones`](docs/milestones).

## Milestones

| | Milestone | Status |
|---|---|---|
| 🔵 | M0 — Foundation | [Done](docs/milestones/M0.md) |
| 🔵 | M1 — Files MCP Server | [Done](docs/milestones/M1.md) |
| ⚪ | M2 — MCP Client | Not started |
| ⚪ | M3 — LLM Integration | Not started |
| ⚪ | M4 — Git MCP | Not started |
| ⚪ | M5 — Code Intelligence | Not started |
| ⚪ | M6 — RAG | Not started |
| ⚪ | M7 — Database MCP | Not started |
| ⚪ | M8 — Kafka MCP | Not started |
| ⚪ | M9 — OpenTelemetry MCP | Not started |
| ⚪ | M10 — Redis MCP | Not started |
| ⚪ | M11 — Kubernetes/OpenShift MCP | Not started |
| ⚪ | M12 — Full Agentic Investigation | Not started |
| ⚪ | M13 — Security & Human Approval | Not started |
| ⚪ | M14 — Production Hardening | Not started |

🔵 = completed · ⚪ = not started. Full plan: [PROMPT.md](PROMPT.md).

## Baseline

| Component     | Version                     |
|---------------|-----------------------------|
| Java          | 25 LTS                      |
| Spring Boot   | 4.1.1 (latest stable 4.1.x) |
| Maven         | 3.9.11 (via `./mvnw`)       |
| MCP Java SDK  | 2.0.1                       |
| Spring AI     | 2.0.1 (MCP server starter)  |

LLM (from M3): any provider via configuration, with local Ollama by default and hosted models (Claude, OpenAI, …) opt-in.
See [ADR-0003](docs/decisions/ADR-0003-model-agnostic-llm.md).

Why these versions: [ADR-0001](docs/decisions/ADR-0001-technology-baseline.md), [ADR-0002](docs/decisions/ADR-0002-mcp-server-stack.md).

## Modules

| Module           | Milestone | Purpose                                                    |
|------------------|-----------|------------------------------------------------------------|
| `devmate-client`    | M0 | Spring Boot app. Will host the MCP client (M2) and agent (M3). |
| `devmate-files-mcp` | M1 | MCP server (Streamable HTTP, `:8091/mcp`): `list_files`, `read_file`, `search_code`, sandboxed and read-only |

Each future module (`devmate-git-mcp`, …) is added when its milestone starts.

## Build & run

```bash
export JAVA_HOME=/path/to/jdk-25        # build fails fast (enforcer) on an older JDK
./mvnw clean verify                     # unit (*Test, surefire) + integration (*IT, failsafe)
java -jar devmate-client/target/devmate-client-*.jar
curl localhost:8080/actuator/health     # {"groups":["liveness","readiness"],"status":"UP"}

# Files MCP server: refuses to start without allowed roots
DEVMATE_FILESYSTEM_ALLOWED_ROOTS=/workspace/projects java -jar devmate-files-mcp/target/devmate-files-mcp-*.jar
```

Files MCP configuration (`devmate.filesystem.*`, limits, security controls): [M1](docs/milestones/M1.md).

## LLM: switch between Anthropic and local Ollama

`devmate-client/src/main/resources/application.yml` has two provider blocks. Exactly one is active:

```yaml
devmate:
  llm:
    # ── Option 1 (ACTIVE): Anthropic Claude. Needs env ANTHROPIC_API_KEY ──
    provider: anthropic
    model: ${DEVMATE_LLM_MODEL:claude-sonnet-5-5}

    # ── Option 2: local Ollama (no API key; ollama serve on localhost:11434) ──
    # provider: ollama
    # model: ${DEVMATE_LLM_MODEL:llama3.2:3b}
```

To switch, comment one block and uncomment the other. You can also override without editing:
`--devmate.llm.provider=ollama --devmate.llm.model=qwen2.5-coder:1.5b`, or `DEVMATE_LLM_MODEL=claude-opus-5-5`.

```bash
export ANTHROPIC_API_KEY=...            # Anthropic only; startup fails with a clear message if missing
java -jar devmate-client/target/devmate-client-*.jar
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
     -d '{"message":"In one sentence: what is Apache Kafka?"}'
# {"provider":"ollama","model":"llama3.2:3b","reply":"Apache Kafka is ...","inputTokens":35,"outputTokens":37,"durationMs":3954}
```

| Variable            | Used by    | Default                  |
|---------------------|------------|--------------------------|
| `ANTHROPIC_API_KEY` | anthropic  | none (required)          |
| `OLLAMA_BASE_URL`   | ollama     | `http://localhost:11434` |
| `DEVMATE_LLM_MODEL` | both       | per block above          |
| `DEVMATE_LLM_TIMEOUT` | anthropic | `120s`                  |

`/api/chat` is plain chat to confirm a provider works. Tool calling and the agent arrive in M3.
Live check against local Ollama: `DEVMATE_LIVE_OLLAMA=true ./mvnw -pl devmate-client verify -Dit.test=OllamaLiveIT`.

## Configuration

Precedence: environment variables > `application-{profile}.yml` > `application.yml`.

| Variable / profile                    | Default | Effect                                      |
|---------------------------------------|---------|---------------------------------------------|
| `DEVMATE_PORT`                        | `8080`  | HTTP port                                   |
| `DEVMATE_SHUTDOWN_TIMEOUT`            | `20s`   | Graceful shutdown grace period              |
| `SPRING_PROFILES_ACTIVE=dev`          | —       | DEBUG logs for `dev.devmate`, health details |
| `SPRING_PROFILES_ACTIVE=json-logs`    | —       | ECS JSON console logs (for containers)      |

Actuator is deny-by-default. Only `health` (with `/liveness` and `/readiness` probes) and `info` are exposed.

## Observability

Every request gets an `X-Request-Id`: a well-formed inbound header is reused, otherwise a UUID is generated.
The id is echoed on the response and added to every log line as `requestId`.
OpenTelemetry tracing (`traceId`/`spanId`) comes in a later milestone.

## Documentation

- [Project prompt & full milestone plan (M0–M14)](PROMPT.md): the original brief DevMate is built from
- [Architecture overview](docs/architecture/overview.md)
- [Decisions (ADRs)](docs/decisions)
- [Milestones](docs/milestones)
