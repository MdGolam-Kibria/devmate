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
- Folder Q&A (client): `mcp/McpToolClient` (SDK sync client, lazy connect, one retry on a new session) →
  `folder/FolderAssistant` maps discovered MCP tools to `llm/LlmTool` (only `readOnlyHint` + a `path` param),
  `FolderScope` pins `path` to the open folder → `SpringAiLlmClient` runs the tool loop itself (Spring AI 2.0 chat
  models never execute tools). Tool options must start from `chatModel.getOptions().mutate()`: options passed with a
  prompt replace the defaults (model was null). Tool errors go back to the model; `McpUnavailableException` aborts.
  Fake model tool keywords: `SEARCH`/`READ`/`LIST`/`TREE`/`FIND <glob>`/`OVERVIEW`/`RANGE <path> <a> <b>`/`LOOP`/`SYSTEM`
  (`fake_ollama.py`). The page shows the whole history: UI cases that assert something is *absent* clear the chat first.
- Folder insight: files MCP tools `folder_tree`/`find_files`/`folder_overview` live in `service/FolderInsights` (bounded
  walks, names/attributes only, never contents); `read_file` takes `startLine`/`endLine` (ranges up to
  `max-range-file-size`), `search_code` takes `contextLines`. Tool outcomes: server logs result counts/flags via
  `FileTools.stats` (strings dropped) and times `devmate.mcp.tool.calls` (tags tool/outcome/reason; expose with
  `DEVMATE_ACTUATOR_EXPOSURE=health,info,metrics`); the client mirrors the same rule in `FolderAssistant.summarize` →
  `LlmTool.Output.summary` → `LlmToolCall.summary/durationMs` → "Tools used" `ChatHistory.ToolUse`. `FolderAssistant`
  puts a `folder_overview` digest in the system prompt when the server offers that tool.
- Folder approval: "outside the allowed roots" → popup → `POST /folder/allow` → `McpToolClient.approveRoot` declares an
  MCP root (in memory, until DevMate restarts). Files MCP honours client roots only with
  `devmate.filesystem.allow-client-roots` (`DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS`, yml default true; then `allowed-roots`
  may be empty and every folder comes from the popup; false + no roots → refuses to start): `access/ClientRoots`
  asks `roots/list` per tool call (`McpSyncRequestContext` param, not in the schema) → `FileAccessPolicy.withAdditionalRoots`.
  The JSON API never approves.
- Folder picker: `ui/FolderBrowser` lists subfolder **names** only (no files, no dot-folders, cap 500) of an absolute dir
  for `GET /folder?browse=`; browsers can't reveal a picked folder's path, so the client does it. It grants nothing:
  "Open this folder" → `POST /folder` (MCP sandbox + popup). Start `devmate.ui.browse-start` (`DEVMATE_BROWSE_START`, home).
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
- 2026-10-05 — Folder Q&A (on request; a slice of M2+M3, both still ⚪): open a folder in the UI (`/folder`, folder bar,
  root chips) or `POST /api/folder/ask`; the model reads/searches it via the files MCP server's tools, listed as "Tools
  used". Config `devmate.mcp.files.url` (`DEVMATE_FILES_MCP_URL`), `devmate.agent.max-tool-rounds` (8),
  `max-tool-result-chars` (30000). 54 unit + 44 IT green; `check-client.sh` 60/60 (UI-35…60), LIVE 64/64 (llama3.2:3b
  called `search_code`); negative check: scope guard disabled → UI-49/50 FAIL. Found: root chips inside the picker
  form sent `path` twice (comma-joined) → one form per chip; blank MCP tool description crashed Spring AI → falls back to name.
- 2026-10-05 — Approve any folder (on request): a folder outside the files MCP roots shows an "Allow DevMate to read this
  folder?" popup; Allow declares it as an MCP root until DevMate restarts (user chose: any folder, until restart). Server
  opt-in `allow-client-roots` (default off: any local MCP client could widen the sandbox). files-mcp 51 unit + 15 IT
  (`ClientRootsIT`), client 58 + 49, `check-client.sh` 69/69 (UI-61…69), M1 check 35/35; negative check: server always
  trusting client roots → UI-64 FAIL. `FileAccessPolicy` needs `@Autowired` on its public constructor (second, private one).
- 2026-10-05 — Files MCP starts with no roots (on request: any folder, granted by the UI popup; running from the IDE
  needed no env). `allow-client-roots` yml default false → true; `FilesProperties` requires `allowed-roots` only when it's
  off; with no roots a relative path is `ACCESS_DENIED` (was `NoSuchElementException`). Trade-off documented: any local
  MCP client can widen its own sandbox; lock down with `…ALLOW_CLIENT_ROOTS=false` + roots. files-mcp 53 unit + 17 IT
  (`ClientRootsOnlyIT`), M1 check 36/36 (TC-01 now pins client roots off, new TC-36), client check 69/69; negative
  check: guard removed → TC-01 FAIL.
- 2026-10-05 — Folder picker (on request: select a folder instead of typing the path): browse with breadcrumb, ⬆ parent,
  per-row Open and "Open this folder"; "Type a path instead" kept, collapsed. Decision: list directories in the client
  (no native dialog can give a page an absolute path); names only, access still via the files MCP sandbox. Client 121
  tests (`FolderBrowserTest`, `FolderChatIT` +5), `check-client.sh` 81/81 (UI-70…81); negative check: listing files and
  dot-folders → UI-72 FAIL. Verified in Chrome: click through → Open this folder → folder opens.
- 2026-10-09 — Folder insight (on request: "Files MCP Server doesn't share enough insight", all four options chosen):
  files MCP gains `folder_tree`, `find_files`, `folder_overview` (new `FolderInsights`), `read_file` line ranges
  (big files read in parts; content cut on a line boundary, `truncated`), `search_code` `contextLines` + `filesMatched`,
  `list_files` `modified`; per-call log stats and the `devmate.mcp.tool.calls` timer (actuator still health/info by
  default). Client: overview digest in the system prompt, "Tools used" shows each call's outcome and ms (errors in
  red), API `toolCalls[].summary/durationMs`. files-mcp 84 unit + 23 IT, client 70 unit + 54 IT; `check-files-mcp.sh`
  56/56 (TC-37…56), `check-client.sh` 89/89 (UI-82…89). Negative checks: tree without policy filter → TC-52 FAIL;
  outcome hidden in UI → UI-82/83 FAIL. Decision: overview walks on every question (bounded by `max-search-files`).
