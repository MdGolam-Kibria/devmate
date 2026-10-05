# CLAUDE.md

DevMate: personal AI developer assistant (MCP client + agent), built milestone by milestone (M0–M14).
Do not start the next milestone without explicit instruction. Each milestone ends with `docs/milestones/M<n>.md`
and the MILESTONE STATUS summary. The full brief and roadmap are in `PROMPT.md`.
Treat it as the source of truth for scope.
When a milestone completes, switch its ⚪ to 🔵 in `PROMPT.md` (roadmap + heading) and in the README Milestones table.

## Build

JDK 25 is not the system default (that is 17): `export JAVA_HOME=~/.jdks/ms-25.0.4.1`, then `./mvnw clean verify`.
`*Test` → surefire, `*IT` → failsafe. Run: `java -jar devmate-client/target/devmate-client-*.jar` (port `DEVMATE_PORT`, default 8080).
Files MCP: `DEVMATE_FILESYSTEM_ALLOWED_ROOTS=<dirs> java -jar devmate-files-mcp/target/devmate-files-mcp-*.jar` → `127.0.0.1:8091/mcp`.
Node.js is not installed, so MCP Inspector is unavailable. Drive servers with the SDK client in an IT, or raw JSON-RPC over curl.

## Conventions

- Versions only in the root POM (parent `spring-boot-starter-parent` 4.1.1). Child POMs declare no versions.
- Package root `dev.devmate.<module>`. Add modules only when their milestone starts.
- MCP servers and the client communicate only over MCP. There are no compile-time dependencies between them.
- Actuator is deny-by-default (`health`, `info`). Never log secrets. Config comes from yml/profiles/env vars.
- MCP servers (ADR-0002): Spring AI MCP server starter (WebMVC, Streamable HTTP, sync) over SDK 2.0.1, pinned by
  importing `mcp-bom` before `spring-ai-bom`. Layout: `tools/` adapter (`@McpTool`, returns `CallToolResult` itself.
  Don't throw: Spring AI duplicates the message) → `service/` (no MCP types) → `access/` policy → validated `config/` record.
  Errors: `isError=true`, text `REASON: detail`. E2E ITs use the official SDK client (`McpClient.sync`).
- LLM (ADR-0003, built in M3): agent depends only on DevMate's `LlmClient` port; one `SpringAiLlmClient` adapter covers all
  providers; `devmate.llm.provider/model` is config only. `application.yml`: Anthropic block active
  (`claude-sonnet-5-5`, env `ANTHROPIC_API_KEY`), Ollama block commented (`llama3.2:3b`; also installed:
  `qwen2.5-coder:1.5b`, `nomic-embed-text`). `spring.ai.*` is derived from `devmate.llm.*`; edit only the top block.
  `LlmCredentialsCheck` fails startup when a hosted provider has no key. Tests use a dummy key
  (`src/test/resources/application.properties`); `OllamaLiveIT` is opt-in (`DEVMATE_LIVE_OLLAMA=true`).
- Correlation: `RequestIdFilter` sets MDC `requestId`. Add new log context (`traceId`, `toolName`, …) through MDC.
- ADRs in `docs/decisions/ADR-NNNN-*.md` with Context / Decision / Alternatives / Consequences.

## Recent Changes

- 2026-10-05 — M0 Foundation: root POM (Java 25, Boot 4.1.1, enforcer, failsafe, Mockito as a javaagent), `devmate-client`
  (Web MVC on virtual threads, Actuator health/probes/info, graceful shutdown, `RequestIdFilter`, `dev`/`json-logs`
  profiles), Maven wrapper 3.9.11, README + architecture + ADR-0001 + M0 doc. 8 unit + 10 IT green.
  Verified for M1/M2: MCP Java SDK 2.0.1 and Spring AI 2.0.1 are compatible with Boot 4.1.1. SDK choice deferred to an ADR.
- 2026-10-05 — M1 Files MCP: `devmate-files-mcp` with `list_files`/`read_file`/`search_code`. Real-path sandbox
  (traversal and symlink escape), secret/VCS/build deny lists, size/binary/limit caps, literal-only search,
  loopback + Origin allowlist. 43 unit + 9 SDK-client ITs green. ADR-0002 (stack/transport).
- 2026-10-05 — ADR-0003: model-agnostic LLM layer (any provider + local Ollama default), scheduled for M3; PROMPT.md M3 amended.
- 2026-10-05 — LLM slice pulled forward from M3 (on request): Anthropic + Ollama switchable via commented yml blocks, `LlmClient` port, `SpringAiLlmClient`, `POST /api/chat`, fail-fast key check. Verified live: Ollama llama3.2:3b answered; Anthropic without key fails at startup. M3 (tools/agent) still ⚪.
