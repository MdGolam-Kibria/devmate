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
| `devmate-client`    | M0 | Spring Boot app: web UI, LLM access, MCP client for folder Q&A. Will host the full MCP client (M2) and agent (M3). |
| `devmate-files-mcp` | M1 | MCP server (Streamable HTTP, `:8091/mcp`): `list_files`, `read_file`, `search_code`, sandboxed and read-only |

Each future module (`devmate-git-mcp`, …) is added when its milestone starts.

## Build & run

```bash
export JAVA_HOME=/path/to/jdk-25        # build fails fast (enforcer) on an older JDK
./mvnw clean verify                     # unit (*Test, surefire) + integration (*IT, failsafe)
java -jar devmate-client/target/devmate-client-*.jar
curl localhost:8083/actuator/health     # {"groups":["liveness","readiness"],"status":"UP"}

# Files MCP server: reads nothing until a folder is approved in the UI (or pre-allowed via DEVMATE_FILESYSTEM_ALLOWED_ROOTS)
java -jar devmate-files-mcp/target/devmate-files-mcp-*.jar
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
curl -s -X POST localhost:8083/api/chat -H 'Content-Type: application/json' \
     -d '{"message":"In one sentence: what is Apache Kafka?"}'
# {"provider":"ollama","model":"llama3.2:3b","reply":"Apache Kafka is ...","inputTokens":35,"outputTokens":37,"durationMs":3954}
```

| Variable            | Used by    | Default                  |
|---------------------|------------|--------------------------|
| `ANTHROPIC_API_KEY` | anthropic  | none (required)          |
| `OLLAMA_BASE_URL`   | ollama     | `http://localhost:11434` |
| `DEVMATE_LLM_MODEL` | both       | per block above          |
| `DEVMATE_LLM_TIMEOUT` | anthropic | `120s`                  |

## Web UI

Open **http://localhost:8083/** for a chat page (Thymeleaf, server-rendered, works without JavaScript).
It opens in your default browser automatically when the app starts (`DEVMATE_OPEN_BROWSER=false` to disable):

- The badge shows the active provider and model. "New chat" clears the conversation.
- Replies are rendered from Markdown (lists, code blocks, tables) with raw HTML escaped and unsafe links removed.
- History lives in your browser session (last 100 messages). Each question is still sent to the model on its own;
  conversation memory arrives with the M3 agent.
- The app listens on `127.0.0.1` by default (`DEVMATE_ADDRESS` to change). There is no login until M13, and the
  session cookie is `HttpOnly` + `SameSite=Strict`.

When overriding from the command line, set **both** provider and model, e.g.
`--devmate.llm.provider=ollama --devmate.llm.model=llama3.2:3b`. Otherwise the model from the active YAML block is kept.

`/api/chat` (JSON) is plain chat to confirm a provider works.

**Ask about a folder** (slice of M2/M3, pulled forward on request): start `devmate-files-mcp`, then in the UI click **📁 Open a folder**, click through to any folder (click **Allow** in the
popup unless it's inside `DEVMATE_FILESYSTEM_ALLOWED_ROOTS`) and ask about its code. The model calls the server's tools (discovered via `tools/list`, pinned to that folder) and each
answer lists the tools it used. JSON: `POST /api/folder/ask {"folder": "…", "question": "…"}`. See the
[client README](devmate-client/README.md#ask-about-a-folder).
Live check against local Ollama: `DEVMATE_LIVE_OLLAMA=true ./mvnw -pl devmate-client verify -Dit.test=OllamaLiveIT`.

## Configuration

Precedence: environment variables > `application-{profile}.yml` > `application.yml`.

| Variable / profile                    | Default | Effect                                      |
|---------------------------------------|---------|---------------------------------------------|
| `DEVMATE_PORT`                        | `8083`  | HTTP port                                   |
| `DEVMATE_SHUTDOWN_TIMEOUT`            | `20s`   | Graceful shutdown grace period              |
| `SPRING_PROFILES_ACTIVE=dev`          | —       | DEBUG logs for `dev.devmate`, health details |
| `SPRING_PROFILES_ACTIVE=json-logs`    | —       | ECS JSON console logs (for containers)      |

Actuator is deny-by-default. Only `health` (with `/liveness` and `/readiness` probes) and `info` are exposed.

## Observability

Every request gets an `X-Request-Id`: a well-formed inbound header is reused, otherwise a UUID is generated.
The id is echoed on the response and added to every log line as `requestId`.
OpenTelemetry tracing (`traceId`/`spanId`) comes in a later milestone.

## Documentation

| Read this | For |
|---|---|
| [docs/DEVELOPER-GUIDE.md](docs/DEVELOPER-GUIDE.md) | **Start here.** Set up, run, use, test, troubleshoot |
| [devmate-client/README.md](devmate-client/README.md) | Chat UI + LLM app: features, **how it works**, how to test it |
| [devmate-files-mcp/README.md](devmate-files-mcp/README.md) | Files MCP server: tools, **how it works**, how to test it |
| [docs/milestones](docs/milestones) | What each milestone delivered and how it was verified |
| [docs/decisions](docs/decisions) | Why: ADR-0001 baseline, ADR-0002 MCP stack, ADR-0003 any-LLM |
| [docs/architecture/overview.md](docs/architecture/overview.md) | Architecture and conventions |
| [PROMPT.md](PROMPT.md) | The original brief and full M0–M14 roadmap |
