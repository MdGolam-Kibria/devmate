# CLAUDE.md

DevMate: personal AI developer assistant (MCP client + agent), built milestone by milestone (M0–M14).
Do not start the next milestone without explicit instruction. Each milestone ends with `docs/milestones/M<n>.md`
and the MILESTONE STATUS summary.

**Every milestone must ship acceptance test cases (mandatory, in addition to unit/IT tests):**
- `docs/testing/M<n>-<name>-test-cases.md`: a fixture description and a table of cases (ID `TC-nn`, steps/request,
  expected result) grouped as: startup & configuration · protocol/API · expected behaviour · security/misuse
  (every refusal case) · transport security & logging. Record the date and result of the last run.
- `scripts/check-<module>.sh`: runs *every* case against the **packaged jar** (not a test context). It builds its own
  fixture, starts the app on a free port, prints PASS/FAIL per case, exits 0 only if all pass, and cleans up
  (`KEEP=1` leaves it running). It must pick a JDK 25 itself, because the shell `JAVA_HOME` here is Java 17.
- Prove the script isn't vacuous: break one safety property on purpose (e.g. bind `0.0.0.0`) and show the related
  cases FAIL.
- The milestone is not COMPLETED until `./mvnw clean verify` **and** the check script both pass. Link the test-case
  doc from `docs/milestones/M<n>.md` and the module README, and list the case count in the MILESTONE STATUS "Tests".
- Templates: `docs/testing/M1-files-mcp-test-cases.md` + `scripts/check-files-mcp.sh`; UI/LLM features:
  `docs/testing/UI-chat-test-cases.md` + `scripts/check-client.sh` (deterministic fake model
  `scripts/support/fake_ollama.py`, keyword-driven: MARKDOWN/XSS/FAIL/echo; `LIVE=1` for real Ollama).
  Shared helpers live in `scripts/support/common.sh` (`pick_jdk`, `result`, `wait_for_health`, `summary`).
- Any UI-facing feature must add example-message cases to `UI-chat-test-cases.md` and `check-client.sh`. The full brief and roadmap are in `PROMPT.md`.
Treat it as the source of truth for scope.
When a milestone completes, switch its ⚪ to 🔵 in `PROMPT.md` (roadmap + heading) and in the README Milestones table.

## Build

JDK 25 is not the system default (that is 17): `export JAVA_HOME=~/.jdks/ms-25.0.4.1`, then `./mvnw clean verify`.
`*Test` → surefire, `*IT` → failsafe. Run: `java -jar devmate-client/target/devmate-client-*.jar` (port `DEVMATE_PORT`, default 8083).
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
  `spring.ai.retry` is capped (`DEVMATE_LLM_MAX_ATTEMPTS`=2) because the default of 10 × 2 s·5 hangs the UI for minutes.
  `LlmCredentialsCheck` fails startup when a hosted provider has no key. Tests use a dummy key
  (`src/test/resources/application.properties`); `OllamaLiveIT` is opt-in (`DEVMATE_LIVE_OLLAMA=true`).
- Web UI (`ui/`): Thymeleaf `templates/chat.html` + `static/css/devmate.css`, `ChatPageController` (POST→redirect→GET),
  `@SessionScope ChatHistory` (cap 100). Assistant text → `MarkdownRenderer` (commonmark, `escapeHtml` + `sanitizeUrls`)
  → `th:utext`; everything else `th:text`. Sessions are cookie-only (`tracking-modes: cookie`, `SameSite=Strict`);
  URL rewriting put `;jsessionid` in redirects, which caused 404s. The client binds `127.0.0.1` by default.
- Local runs: never `pkill -f`/`pgrep -f` with a pattern that's in your own command line (it kills the shell, exit 144).
  Start with `&`, save `$!`, kill that PID.
- `BrowserLauncher` opens the UI on `ApplicationReadyEvent` (`devmate.ui.open-browser`, env `DEVMATE_OPEN_BROWSER`,
  default true). It's disabled in tests via `src/test/resources/application.properties`. LLM tests set the provider
  explicitly so they pass whichever yml block is active. Developer-facing docs: `docs/DEVELOPER-GUIDE.md`.
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
- 2026-10-05 — Thymeleaf chat UI at `/`: provider/model badge, safe Markdown replies, session history, PRG,
  loopback binding, cookie-only Strict sessions, `Enable-Native-Access` manifest (Netty warning on JDK 25). Verified in Chrome against Ollama llama3.2:3b. 103 tests green.
- 2026-10-05 — Developer guide (`docs/DEVELOPER-GUIDE.md`), browser auto-open on startup, default port 8083, provider-independent LLM tests.
- 2026-10-05 — M1 acceptance cases (`docs/testing/M1-files-mcp-test-cases.md`, 35 cases) automated in `scripts/check-files-mcp.sh` (35/35 PASS; negative check fails TC-03/TC-33 as expected). New rule: every milestone ships the same.
- 2026-10-05 — Web UI example-message cases (`docs/testing/UI-chat-test-cases.md`, 34 + 3 live) automated in `scripts/check-client.sh` with a fake Ollama; 34/34 and 37/37 (LIVE) PASS. Found and fixed: Spring AI default retry hung provider errors for minutes, now capped.
