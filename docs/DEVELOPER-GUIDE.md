# DevMate developer guide

How to build, run, use and test DevMate as it stands today. For the full roadmap see [PROMPT.md](../PROMPT.md); for
design decisions see [docs/decisions](decisions).

---

## 1. What exists today

| | Piece | What it does |
|---|---|---|
| 🔵 | **M0 Foundation** | Maven multi-module build (Java 25, Spring Boot 4.1.1), `devmate-client` app with Actuator health, request ids, structured logs |
| 🔵 | **M1 Files MCP server** (`devmate-files-mcp`) | MCP server with `list_files`, `read_file`, `search_code`, sandboxed to configured folders, read-only |
| ➕ | **LLM switch** (pulled forward from M3) | `devmate-client` talks to **Anthropic Claude** or **local Ollama**, chosen in `application.yml` |
| ➕ | **Web UI** | Chat page at `http://localhost:8083/` (Thymeleaf), opens in your browser on startup |
| 🔵 | **M2 MCP client** | Connects to any number of MCP servers, discovers and registers their tools, calls them; typed errors; `/api/mcp` works without an LLM |
| ➕ | **Ask about a folder** (slice of M3) | Open a folder in the UI; the model reads/searches it through the files MCP server's tools |
| ⚪ | M3 agent, … | Milestones still open: only the folder slice of the agent exists (no multi-turn memory) |

```text
 Browser ──► devmate-client :8083 ──► LLM (Anthropic API  or  Ollama :11434)
             (UI, /api/chat,          ▲ tool calls / results
              /api/folder/ask,        │
              /api/mcp)               │
                    └── MCP client ───┴──► devmate-files-mcp :8091/mcp ──► allowed folders (read-only)
```

---

## 2. Prerequisites

| Tool | Version | On this machine |
|---|---|---|
| JDK | **25** | `~/.jdks/ms-25.0.4.1`. The system `java` is 17, so always set `JAVA_HOME` |
| Maven | 3.9+ | Use the wrapper `./mvnw` (pinned to 3.9.11); no install needed |
| Ollama | any | Optional. `ollama serve`; models installed: `llama3.2:3b`, `qwen2.5-coder:1.5b`, `nomic-embed-text` |
| Anthropic API key | | Optional. Only if you use the Anthropic block |
| Docker | | Not needed yet |

```bash
export JAVA_HOME=~/.jdks/ms-25.0.4.1     # put this in ~/.bashrc to avoid repeating it
cd ~/kibria/devmate
```

If the JDK is wrong, the build stops immediately with
`DevMate requires JDK 25+ (set JAVA_HOME)`.

**IntelliJ:** File → Project Structure → SDK = `ms-25.0.4.1`. Settings → Build Tools → Maven → Runner → JRE = the same SDK.

---

## 3. Build

```bash
./mvnw clean verify                         # everything: compile + unit + integration tests (≈1 min)
./mvnw -pl devmate-client -am package -DskipTests      # just build the client jar
./mvnw -pl devmate-files-mcp -am package -DskipTests   # just build the files MCP jar
```

The jars are written to `devmate-client/target/devmate-client-0.1.0-SNAPSHOT.jar` and
`devmate-files-mcp/target/devmate-files-mcp-0.1.0-SNAPSHOT.jar`.

---

## 4. Run and use the chat app (`devmate-client`)

### 4.1 Pick the model

Edit `devmate-client/src/main/resources/application.yml`. Exactly one block is active:

```yaml
devmate:
  llm:
    # ── Option 1: Anthropic Claude. Needs env ANTHROPIC_API_KEY ──
    provider: anthropic
    model: ${DEVMATE_LLM_MODEL:claude-sonnet-5-5}

    # ── Option 2: local Ollama (no API key) ──
    # provider: ollama
    # model: ${DEVMATE_LLM_MODEL:llama3.2:3b}
```

To switch, comment one block and uncomment the other. Keep the 4-space indentation under `llm:`.

| Want | Do |
|---|---|
| Claude | `export ANTHROPIC_API_KEY=sk-ant-…`. Without it, startup stops with a message telling you so |
| Another Claude model | `DEVMATE_LLM_MODEL=claude-opus-5-5` |
| Local Ollama | Uncomment the Ollama block; make sure `ollama serve` is running and the model is pulled (`ollama list`) |
| Another Ollama model | `DEVMATE_LLM_MODEL=qwen2.5-coder:1.5b` |
| Ollama on another host | `OLLAMA_BASE_URL=http://host:11434` |
| Switch without editing the file | `--devmate.llm.provider=ollama --devmate.llm.model=llama3.2:3b`. Set **both**, or the model from the active block stays |

### 4.2 Start it

```bash
java -jar devmate-client/target/devmate-client-0.1.0-SNAPSHOT.jar
```

or in IntelliJ, run `dev.devmate.client.DevMateClientApplication`.

When it's ready, the log prints `DevMate UI ready at http://localhost:8083/` and **your default browser opens the
page**. The browser does not open when:
- you set `DEVMATE_OPEN_BROWSER=false` (useful when restarting often from the IDE),
- there is no desktop session (SSH, server, container), in which case it just logs the address,
- tests are running (always disabled there).

| Setting | Env var | Default |
|---|---|---|
| Port | `DEVMATE_PORT` (or `--server.port=`) | `8083` |
| Listen address | `DEVMATE_ADDRESS` | `127.0.0.1` (only this machine can reach it) |
| Auto-open browser | `DEVMATE_OPEN_BROWSER` | `true` |
| LLM timeout (Anthropic) | `DEVMATE_LLM_TIMEOUT` | `120s` |
| Session length | `DEVMATE_SESSION_TIMEOUT` | `8h` |
| LLM attempts on provider error | `DEVMATE_LLM_MAX_ATTEMPTS` | `2` |
| Debug logs + health details | `SPRING_PROFILES_ACTIVE=dev` | off |
| JSON logs | `SPRING_PROFILES_ACTIVE=json-logs` | off |

**Where to put settings.** Prefer environment variables or the `${…:default}` lines that already exist.
`application.yml` is split by `---` lines, and **when the same key appears twice, the later section wins**. Adding a
second `server.port` near the top of the file therefore has no effect. Profile files (`application-dev.yml`) load
only when that profile is active (`--spring.profiles.active=dev`, or "Active profiles" in IntelliJ).

### 4.3 Use the UI

- Type a question and press **Enter** (Shift+Enter for a new line), or click a suggestion.
- The badge at the top shows the active provider and model. Under each answer you see model, tokens and time.
- **New chat** clears the conversation. History is per browser session, up to the last 100 messages.
- Each question is answered on its own: the model does not remember earlier messages yet (that comes with the M3 agent).
- The footer says where your text goes: local Ollama, or the hosted provider.

### 4.3a Ask questions about a folder

1. Start the files MCP server (section 5): `java -jar devmate-files-mcp/target/devmate-files-mcp-*.jar`. With no
   roots it can read nothing until you approve a folder in the UI; optionally pre-allow folders with
   `DEVMATE_FILESYSTEM_ALLOWED_ROOTS=$HOME/projects`.
2. Start DevMate as usual. It finds the server at `DEVMATE_FILES_MCP_URL` (default `http://127.0.0.1:8091/mcp`).
   Start order doesn't matter: DevMate connects on first use and reconnects if the server restarts.
3. In the UI click **📁 Open a folder**, click through the folders (breadcrumb, ⬆ for the parent) and press **Open this
   folder** (or **Open** on a row). **Type a path instead** is still there. The picker starts in your home folder
   (`DEVMATE_BROWSE_START`).
4. Ask, e.g. *"What does this project do?"*, *"Where is Kafka used?"*, *"এই project-এ Kafka কোথায় ব্যবহার করা হয়েছে?"*.
   Expand **Tools used** under the answer to see each call (`folder_overview`, `folder_tree`, `find_files`,
   `search_code`, `read_file`, `list_files`) with its outcome, e.g. `→ matches=3 filesScanned=40 truncated=false · 12 ms`,
   or `→ error: ACCESS_DENIED: …` in red. DevMate also puts a short folder overview (file counts, file types, build and
   config files, recently changed files) into the model's system prompt, so even a small model knows the project.
5. **Close folder** returns to plain chat. The open folder is per browser session.

**A folder outside the allowed roots** (every folder, when none are configured): opening it shows a popup, **"Allow
DevMate to read this folder?"**. Click **Allow** and it opens (read-only, secrets still hidden) until DevMate
restarts. This is the default (`DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=true`). To lock the server down to fixed folders,
start it with `DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=false DEVMATE_FILESYSTEM_ALLOWED_ROOTS=…`; Allow then says the
server didn't accept the approval.

The model can't leave the folder: `../`, other roots and `/etc/...` are refused with
`ACCESS_DENIED: path is outside the selected folder`, and the server still hides `.env`, keys, `.git`, `target`.
With Anthropic, file contents the tools return are sent to Anthropic (the footer says so); with Ollama nothing
leaves the machine. Small local models are slow with tools on CPU (about 60 s for llama3.2:3b) and sometimes don't
call a tool. Naming one in the question ("use search_code") helps.

Same thing from a script:

```bash
curl -s -X POST localhost:8083/api/folder/ask -H 'Content-Type: application/json' \
     -d '{"folder":"/home/me/projects/shop","question":"Where is Kafka used?"}' | jq '{reply, toolCalls}'
curl -s localhost:8083/api/folder/roots          # folders the server allows
```

### 4.4 Use the JSON API

```bash
curl -s -X POST localhost:8083/api/chat -H 'Content-Type: application/json' \
     -d '{"message":"In one sentence: what is Apache Kafka?"}'
# {"provider":"ollama","model":"llama3.2:3b","reply":"…","inputTokens":35,"outputTokens":37,"durationMs":3954}
```

| Status | Meaning |
|---|---|
| 200 | Answer |
| 400 `INVALID_REQUEST` | Empty message or more than 8000 characters |
| 502 `LLM_UNAVAILABLE` | Provider failed (Ollama down, unknown model, bad key…). `message` says why |

Health: `curl localhost:8083/actuator/health` returns `{"status":"UP",…}`.

**MCP without an LLM (M2).** With the files MCP server running:

```bash
curl -s localhost:8083/api/mcp/servers | jq            # each server: state, serverName, protocolVersion, tools, error
curl -s localhost:8083/api/mcp/tools | jq '.tools[] | {server, name, readOnly}'
curl -s -X POST localhost:8083/api/mcp/tools/search_code/call -H 'Content-Type: application/json' \
     -d '{"arguments":{"query":"Kafka"}}' | jq
# {"tool":"search_code","server":"files","error":false,"errorCode":null,"text":"{\"query\":\"Kafka\",\"matches\":[…]}","durationMs":41}
```

| Status | `errorCode` / `code` | Meaning |
|---|---|---|
| 200 | – / `TOOL_ERROR` | The tool ran; `TOOL_ERROR` = it reported a failure (`text` = `NOT_FOUND: …`, `ACCESS_DENIED: …`) |
| 400 | `INVALID_ARGUMENTS` | Arguments don't match the tool's `inputSchema` (checked before sending) or the server rejected them |
| 403 | `TOOL_NOT_ALLOWED` | The tool isn't declared read-only; DevMate won't run it before M13 |
| 404 | `UNKNOWN_TOOL` | No server offers it |
| 502 / 504 | `SERVER_ERROR` / `TIMEOUT` | JSON-RPC error from the server / no answer within `request-timeout` |
| 503 | `MCP_UNAVAILABLE` + `CONNECTION_FAILED`, `SESSION_ERROR`, `TRANSPORT_ERROR`, `TIMEOUT` | The server can't be used; `message` says which and why |

Add another MCP server with `--devmate.mcp.servers.<name>.url=http://127.0.0.1:<port>/mcp` (and `.priority` if its
tool names clash with `files`, which has priority 10). See [ADR-0004](decisions/ADR-0004-mcp-client.md).

---

## 5. Run and use the files MCP server (`devmate-files-mcp`)

```bash
DEVMATE_FILESYSTEM_ALLOWED_ROOTS=/home/golamkibria/kibria/devmate \
  java -jar devmate-files-mcp/target/devmate-files-mcp-0.1.0-SNAPSHOT.jar
# MCP endpoint: http://127.0.0.1:8091/mcp   (Streamable HTTP)
```

`DEVMATE_FILESYSTEM_ALLOWED_ROOTS` (comma-separated absolute folders) is optional: without it the server starts with
nothing readable, and DevMate's **Allow** popup grants folders one by one (`allow-client-roots`, default `true`). With
`DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=false` the roots are required and it refuses to start without them. Nothing
outside the allowed/approved folders is reachable, and secrets (`.env`, `*.pem`, `id_rsa`, …) plus `.git`/`target`/`node_modules` are hidden.

| Tool | Arguments | Example |
|---|---|---|
| `list_files` | `path?` | No path lists the allowed roots |
| `read_file` | `path` | `devmate-client/pom.xml` (relative to the first root) |
| `search_code` | `query`, `path?`, `glob?` | `{"query":"KafkaTemplate","glob":"*.java"}` |

### Try it with curl (raw MCP)

```bash
H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')

# 1. initialize; note the Mcp-Session-Id response header
curl -s -D /tmp/h "${H[@]}" localhost:8091/mcp -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
  "params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
SID=$(grep -i '^mcp-session-id' /tmp/h | cut -d' ' -f2 | tr -d '\r')

# 2. finish the handshake
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" localhost:8091/mcp -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3. list tools
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" localhost:8091/mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 4. call a tool
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" localhost:8091/mcp -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
  "params":{"name":"search_code","arguments":{"query":"RequestIdFilter","glob":"*.java"}}}'
```

Errors come back as `"isError":true` with one line such as `ACCESS_DENIED: path is outside the allowed roots`.

### Try it with MCP Inspector (needs Node.js)

```bash
DEVMATE_HTTP_ALLOWED_ORIGINS=http://localhost:6274 DEVMATE_FILESYSTEM_ALLOWED_ROOTS=$PWD \
  java -jar devmate-files-mcp/target/devmate-files-mcp-*.jar
npx @modelcontextprotocol/inspector     # transport: Streamable HTTP, URL: http://localhost:8091/mcp
```

Browser requests from origins that aren't allow-listed get `403` (protection against DNS rebinding).

---

## 6. Testing

### 6.1 Commands

```bash
./mvnw clean verify                                          # all tests, both modules
./mvnw -pl devmate-client verify                             # one module
./mvnw -pl devmate-client test -Dtest=MarkdownRendererTest   # one unit test class
./mvnw -pl devmate-client verify -Dit.test=ChatPageIT   # one integration test class
DEVMATE_LIVE_OLLAMA=true ./mvnw -pl devmate-client verify -Dit.test=OllamaLiveIT  # real Ollama call
```

Reports: `*/target/surefire-reports` (unit), `*/target/failsafe-reports` (integration).

### 6.2 What the tests cover (278)

| Module | Suite | Kind | Covers |
|---|---|---|---|
| client | `RequestIdFilterTest` | unit | Request id reuse/replace, log-injection safety |
| client | `ActuatorHealthIT` | IT | Health/liveness/readiness UP; sensitive endpoints hidden |
| client | `SpringAiLlmClientTest` | unit | Prompt building, usage mapping, provider errors |
| client | `LlmProviderSwitchIT` | IT | Anthropic and Ollama blocks activate the right model; missing key fails fast |
| client | `ChatControllerIT` | IT | `/api/chat` 200/400/502 |
| client | `ChatPageIT` | IT | UI: send, history per session, clear, HTML escaping, Markdown, cookie flags |
| client | `MarkdownRendererTest` | unit | Lists/code/tables render; `<script>`, `javascript:` neutralised |
| client | `BrowserLauncherTest` | unit | Right open command per OS; nothing on headless Linux |
| client | `OllamaLiveIT` | live, opt-in | Real round trip to local Ollama |
| client | `McpClientServiceIT` | IT | MCP client over real Streamable HTTP: discovery, registry, execution, every error code, reconnect, roots |
| client | `McpControllerIT` | IT | `/api/mcp` through the whole app against a real MCP server and a down one |
| client | `ToolExecutorTest`, `ToolRegistryTest`, `McpConnectionTest`, `McpClientPropertiesTest`, `HostHeaderFilterTest` | unit | Policy, schema check, error mapping, cache, priority, failure classification, config, DNS-rebinding guard |
| files-mcp | `FileAccessPolicyTest` | unit | Traversal, symlink escape, secrets, invalid paths |
| files-mcp | `FileServiceTest` | unit | List/read/search behaviour and limits |
| files-mcp | `FileToolsTest` | unit | MCP result/error format |
| files-mcp | `FilesPropertiesTest` | unit | Config binding and validation |
| files-mcp | `FilesMcpServerIT` | IT | Real MCP SDK client: initialize → tools/list → tools/call |

### 6.3 Rules for writing tests

- `FooTest` = unit test (surefire, fast, no Spring context). `FooIT` = boots Spring or a server (failsafe).
- **Never call a real LLM in normal tests.** Replace it with `@MockitoBean LlmClient llm;`. A dummy Anthropic key
  lives in `devmate-client/src/test/resources/application.properties`, so contexts start offline.
- Tests that care about the provider must set it themselves
  (`properties = {"devmate.llm.provider=anthropic", …}`), so switching the YAML block never breaks the build.
- File-server tests build their own fake project with `TestWorkspace.create(tempDir)`.
- MCP end-to-end tests use the official SDK client (`McpClient.sync(HttpClientStreamableHttpTransport…)`).

### 6.4 Acceptance checks (is it really working?)

Each milestone has a test-case document and a script that runs every case against the **packaged app**:

| Milestone | Test cases | Run |
|---|---|---|
| M1 Files MCP | [docs/testing/M1-files-mcp-test-cases.md](testing/M1-files-mcp-test-cases.md) (56) | `./scripts/check-files-mcp.sh` |
| M2 MCP client | [docs/testing/M2-mcp-client-test-cases.md](testing/M2-mcp-client-test-cases.md) (42) | `./scripts/check-mcp-client.sh` (real files MCP + fake misbehaving MCP, no LLM) |
| Web UI chat | [docs/testing/UI-chat-test-cases.md](testing/UI-chat-test-cases.md) (89 + 4 live) | `./scripts/check-client.sh` (`LIVE=1` for real Ollama) |

```text
$ ./scripts/check-files-mcp.sh
  PASS  TC-01  refuses to start without DEVMATE_FILESYSTEM_ALLOWED_ROOTS when client roots are off
  …
  PASS  TC-20  read_file("escape.txt") symlink pointing outside → ACCESS_DENIED
  …
ALL 35 CASES PASSED — the Files MCP server is working.
```

`KEEP=1 ./scripts/check-files-mcp.sh` leaves the server and fixture running so you can try your own calls.

### 6.5 Manual smoke test (5 minutes)

1. `./mvnw clean verify` ends with **BUILD SUCCESS**.
2. Start the client (§4.2). The browser opens, and the badge shows your provider/model.
3. Ask *"List 3 causes of Kafka consumer lag"*. The answer renders as a numbered list, with tokens and time underneath.
4. Ask *"Reply with `<script>alert(1)</script>`"*. You see the text; no alert pops up.
5. Click **New chat**. The conversation clears.
6. Start the files MCP server (§5) and run the curl steps. `search_code` returns matches, and `read_file` of
   `../../etc/passwd` returns `ACCESS_DENIED`.

---

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `DevMate requires JDK 25+` | `export JAVA_HOME=~/.jdks/ms-25.0.4.1` |
| `Port 8083 was already in use` | Another DevMate is running (often from IntelliJ). Stop it or use `DEVMATE_PORT=8093` |
| Port setting ignored | You added a second `server.port` in `application.yml`; the later `---` section wins. Use `DEVMATE_PORT` or edit the existing line |
| `needs an API key: set the ANTHROPIC_API_KEY…` | Export the key, or switch to the Ollama block |
| 502 `Connection refused` | Ollama isn't running: `ollama serve` |
| 502 mentioning model not found | Model not pulled: `ollama pull llama3.2:3b`, or fix `DEVMATE_LLM_MODEL` |
| Badge shows `ollama / claude-…` | You overrode only the provider on the command line; also pass `--devmate.llm.model=` |
| Answers are poor or wrong | Small local models (1.5–3B) are weak. Try a bigger model or Claude |
| Browser didn't open | No desktop session, or `DEVMATE_OPEN_BROWSER=false`. Open the logged URL manually |
| Files MCP won't start | With `DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=false`, set `DEVMATE_FILESYSTEM_ALLOWED_ROOTS`; each folder must exist |
| "Files MCP server is not reachable at …" | Start `devmate-files-mcp`, or point `DEVMATE_FILES_MCP_URL` at it |
| 403 `HOST_NOT_ALLOWED` | You reached DevMate by a name other than `localhost`/`127.0.0.1`/`[::1]` (e.g. after changing `DEVMATE_ADDRESS`): add it to `DEVMATE_ALLOWED_HOSTS` |
| 403 `TOOL_NOT_ALLOWED` from `/api/mcp` | The server doesn't mark that tool `readOnlyHint=true`; DevMate only runs read-only tools until M13 |
| A tool from a new server isn't used | Another server with a lower `priority` offers the same name (log: `MCP tool name clash`) |
| Opening a folder says `ACCESS_DENIED: … outside the allowed roots` | Click **Allow** in the popup (needs `DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=true` on the server, the default), or add it to `DEVMATE_FILESYSTEM_ALLOWED_ROOTS` and restart the server. Roots are comma-separated **absolute** paths: `/a,/home/me/b`, not `/a,me/b` |
| Allow says "did not accept the approval" | The files MCP server runs with `DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=false`, or the folder doesn't exist |
| Folder answer has no "Tools used" | The model answered without looking. Ask it to "use search_code", or use a stronger model |
| MCP Inspector gets 403 | Add its origin: `DEVMATE_HTTP_ALLOWED_ORIGINS=http://localhost:6274` |

---

## 8. Project layout

```text
devmate/
├── pom.xml                      versions for everything (Boot, Spring AI, MCP SDK, commonmark)
├── PROMPT.md                    full brief + roadmap (🔵 done / ⚪ todo)
├── README.md                    overview + quick start
├── scripts/check-*.sh           acceptance checks per milestone (run against the real jar)
├── docs/
│   ├── DEVELOPER-GUIDE.md       this file
│   ├── testing/M*-test-cases.md acceptance test cases per milestone
│   ├── architecture/overview.md
│   ├── decisions/ADR-000*.md    why things are the way they are
│   └── milestones/M*.md         what each milestone delivered
├── devmate-client/              chat app (port 8083)
│   └── src/main/java/dev/devmate/client/
│       ├── llm/                 LlmClient port, SpringAiLlmClient, /api/chat, config + key check
│       ├── ui/                  Thymeleaf page controller, session history, Markdown renderer, browser launcher
│       └── web/                 request-id filter
│   └── src/main/resources/      application.yml, templates/chat.html, static/css/devmate.css
└── devmate-files-mcp/           MCP server (port 8091)
    └── src/main/java/dev/devmate/files/
        ├── tools/               @McpTool adapter (list_files, read_file, search_code)
        ├── service/             file logic, no MCP types
        ├── access/              sandbox policy (the one security gate)
        ├── config/              validated settings
        └── web/                 Origin check
```

## 9. Conventions (short version)

- All versions live in the root `pom.xml`; child POMs never declare versions.
- No secrets in files. Keys come from environment variables.
- New MCP tool: add a method in the module's `tools/` class with `@McpTool`, put the logic in `service/`, route every
  path or resource through the policy, then add unit tests + an IT call.
- New LLM provider: add its Spring AI starter to `devmate-client/pom.xml` and a commented block in `application.yml`.
  No Java change is needed unless it requires an API-key check (`LlmCredentialsCheck`).
- Every finished milestone updates `docs/milestones/M<n>.md`, flips ⚪→🔵 in `PROMPT.md`/README, and adds a line to
  `CLAUDE.md` "Recent Changes".
