# devmate-client

The DevMate application you interact with: a **chat web UI** and **JSON API** in front of a configurable LLM
(Anthropic Claude or local Ollama). It runs on **http://localhost:8083/** and opens in your browser on startup.

> **Ask about a folder:** open a folder in the UI and ask questions about its code. DevMate connects to the
> [files MCP server](../devmate-files-mcp/README.md) over MCP, gives the model its tools (discovered at runtime,
> pinned to that folder), and the model searches and reads the files to answer. This is a slice of M2 (MCP client)
> and M3 (agent) pulled forward on request; both milestones are still open (⚪).

---

## Features implemented

| Feature | Where | Notes |
|---|---|---|
| Chat web UI | `ui/ChatPageController`, `templates/chat.html`, `static/css/devmate.css` | Thymeleaf, server-rendered, works without JS; light/dark |
| Markdown replies | `ui/MarkdownRenderer` | Lists, code, tables; raw HTML escaped, `javascript:` links removed |
| Conversation history | `ui/ChatHistory` | Per browser session, last 100 messages, "New chat" clears it |
| Open browser on startup | `ui/BrowserLauncher` | `xdg-open` / `open` / `rundll32`; off in tests and headless |
| JSON chat API | `llm/ChatController` | `POST /api/chat` → reply + model + tokens + time |
| **Ask about a folder** | `folder/FolderAssistant`, `ui/SelectedFolder`, `ui/FolderBrowser`, folder bar in `chat.html` | Click a folder in the picker (or type its path), ask; "Tools used (N)" under each answer |
| Folder JSON API | `folder/FolderController` | `POST /api/folder/ask {folder, question}`, `GET /api/folder/roots` |
| Folder scope | `folder/FolderScope` | Tool `path` pinned to the open folder; `../`, other roots, `/etc` refused |
| Approve any folder (popup) | `POST /folder/allow`, `FolderAssistant.allow`, `McpToolClient.approveRoot` | Folder outside the server's roots → "Allow DevMate to read this folder?" → declared as an MCP root until DevMate restarts |
| MCP client | `mcp/McpToolClient` | Official SDK, Streamable HTTP; lazy connect, reconnects after a server restart |
| Tool-calling loop | `llm/SpringAiLlmClient` | Model ↔ tools, max 8 rounds, tool output cut at 30 000 chars |
| Any LLM by config | `llm/LlmClient`, `llm/SpringAiLlmClient`, `application.yml` | Anthropic block active, Ollama block commented |
| API-key check | `llm/LlmCredentialsCheck` | Anthropic without a key stops startup with a clear message |
| Request ids | `web/RequestIdFilter` | `X-Request-Id` on every response, `requestId` in every log line |
| Health | Actuator | `/actuator/health`, `/liveness`, `/readiness`, `/info`; nothing else exposed |
| Safe defaults | `application.yml` | Binds `127.0.0.1`, cookie-only `HttpOnly` + `SameSite=Strict` sessions, no stack traces |

---

## How it works

### A message from the UI

```text
Browser                     devmate-client                                         LLM
───────                     ──────────────                                         ───
POST /chat  message=…  ──►  RequestIdFilter     adds X-Request-Id, MDC requestId
                            ChatPageController  validate 1–8000 chars
                              │ ChatHistory.add(USER, question)
                              ▼
                            LlmClient.chat(LlmRequest)        ← interface, no provider types
                              │
                            SpringAiLlmClient                 ← the only adapter
                              │ builds Prompt(SystemMessage?, UserMessage)
                              ▼
                            Spring AI ChatModel  ─────────────────────────────►  Anthropic API
                            (AnthropicChatModel or OllamaChatModel,               or Ollama :11434
                             whichever spring.ai.model.chat selects)  ◄────────  answer + token usage
                              │
                            LlmResponse(text, model, inputTokens, outputTokens)
                              │
                            MarkdownRenderer.toSafeHtml(text)
                            ChatHistory.add(ASSISTANT, text, html, "model · tokens · ms")
                              │
◄── 302 redirect to /  ─────┘          (post/redirect/get: refresh never re-sends)
GET /  ───────────────────► chat.html renders ChatHistory
                              user + error text: th:text  (escaped)
                              assistant html:    th:utext (already sanitised)
```

If the provider fails, `SpringAiLlmClient` throws `LlmException` with the root cause ("Connection refused",
"401 …"). The UI shows it as a red error message; the JSON API returns `502 LLM_UNAVAILABLE`.

### A question about a folder

```text
POST /folder path=/home/me/shop   ChatPageController → FolderAssistant.open
                                    └─ MCP tools/call list_files {path}  ──► files MCP: inside allowed roots?
                                       real path + top-level entries  ◄──    exists? a directory?
                                  SelectedFolder (session) = real path

POST /chat "Where is Kafka used?"  FolderAssistant.ask
                                    ├─ list_files again (folder still allowed?), entries → system prompt
                                    ├─ MCP tools/list → keep tools with readOnlyHint=true and a `path` parameter
                                    ├─ folder_overview (if offered) → digest in the system prompt
                                    │                   → LlmTool(name, description, inputSchema, handler)
                                    └─ LlmClient.chat(system, question, tools, maxToolRounds=8)
                                         SpringAiLlmClient loop:
                                           model → tool call search_code {"query":"Kafka"}
                                           handler: FolderScope pins path to /home/me/shop (refuses ../, /etc)
                                                    MCP tools/call  ──► files MCP (real-path sandbox, deny lists)
                                           result (cut at 30 000 chars) → model → … → final answer
                                  ChatHistory: answer + "Tools used (N)": each call → outcome · ms
```

- **No tool is hard-coded in the agent's tool list.** It comes from `tools/list` on every question. DevMate offers
  only tools that are read-only and take a `path`, because those are the ones it can pin to the folder. Two tools are
  called by name: opening a folder uses `list_files`, and if the server offers `folder_overview` its result goes into
  the system prompt as a short digest (`42 files in 7 directories, 200 KB` · top file types · build/config files ·
  recently modified). So even a model that calls no tools knows what kind of project it's in.
- **Tool outcomes are visible.** Each "Tools used" line shows the call and its outcome: the result's top-level counts
  and flags (`matches=3 filesScanned=40 truncated=false`, `size=… lineCount=…`), or `error: <REASON: detail>` in red,
  plus the duration. Never the content. `/api/folder/ask` returns the same as `toolCalls[].summary` / `durationMs`.
- **Two boundaries:** the files MCP server enforces the real security sandbox (allowed roots, real paths and
  symlinks, `.env`/keys denied, `target/` and `.git/` hidden). `FolderScope` also keeps the model inside the folder
  you opened, even when the server allows more. Its check is lexical: a symlink inside the folder that points
  elsewhere inside the allowed roots is still readable. The server's real-path check is the hard limit.
- Tool errors (`ACCESS_DENIED: …`, `NOT_FOUND: …`) go back to the model as tool results, so it can try something
  else. A server that's down stops the answer with "Files MCP server is not reachable at …".
- Logs show tool name, outcome and latency, never arguments or file contents. The files MCP server logs each call's
  result counts too, and can expose a per-tool timer (see its README).

### How the provider switch works

`application.yml` has one place you edit and one derived "wiring" section:

```yaml
devmate:
  llm:
    provider: anthropic                              # ← you change these two lines
    model: ${DEVMATE_LLM_MODEL:claude-sonnet-5-5}
    # provider: ollama
    # model: ${DEVMATE_LLM_MODEL:llama3.2:3b}
---
spring:
  ai:
    model:
      chat: ${devmate.llm.provider}                  # Spring AI activates ONLY this provider's ChatModel
    anthropic.chat.model: ${devmate.llm.model}
    ollama.chat.model:    ${devmate.llm.model}
```

Both provider libraries are on the classpath, but `spring.ai.model.chat` decides which `ChatModel` bean exists.
`SpringAiLlmClient` receives whichever one that is, so no Java code knows or cares which provider is active.
Adding another provider (OpenAI, Gemini, …) means adding its Spring AI starter and another commented block.

### How the browser opens

`BrowserLauncher` listens for `ApplicationReadyEvent`, which fires after the server is accepting requests. It reads
the real port, logs `DevMate UI ready at http://localhost:<port>/` and, if `devmate.ui.open-browser` is true and a
desktop session exists (`DISPLAY`/`WAYLAND_DISPLAY` on Linux), runs the OS "open URL" command on a virtual thread.
A failure only logs a message; startup is never affected.

---

## Run

```bash
export JAVA_HOME=~/.jdks/ms-25.0.4.1
./mvnw -pl devmate-client -am package -DskipTests        # from the repo root
export ANTHROPIC_API_KEY=sk-ant-…                        # only for the Anthropic block
java -jar devmate-client/target/devmate-client-0.1.0-SNAPSHOT.jar
```

| Setting | Env var | Default |
|---|---|---|
| Port | `DEVMATE_PORT` | `8083` |
| Listen address | `DEVMATE_ADDRESS` | `127.0.0.1` |
| Open browser | `DEVMATE_OPEN_BROWSER` | `true` |
| Model (either provider) | `DEVMATE_LLM_MODEL` | from the active block |
| Ollama URL | `OLLAMA_BASE_URL` | `http://localhost:11434` |
| Anthropic key | `ANTHROPIC_API_KEY` | none |
| Attempts on provider error | `DEVMATE_LLM_MAX_ATTEMPTS` | `2` (Spring AI default 10 would hang minutes) |
| Files MCP endpoint | `DEVMATE_FILES_MCP_URL` | `http://127.0.0.1:8091/mcp` |
| Files MCP request timeout | `DEVMATE_FILES_MCP_TIMEOUT` | `30s` |
| Tool rounds per question | `DEVMATE_AGENT_MAX_TOOL_ROUNDS` | `8` |
| Longest tool output sent to the model | `DEVMATE_AGENT_MAX_TOOL_RESULT_CHARS` | `30000` |

IntelliJ: run `dev.devmate.client.DevMateClientApplication` with SDK `ms-25.0.4.1`; set env vars in the run configuration.

## Use

- **UI:** type a question, press Enter. The badge shows provider/model; each answer shows tokens and time.
- **API:**
  ```bash
  curl -s -X POST localhost:8083/api/chat -H 'Content-Type: application/json' -d '{"message":"What is Kafka?"}'
  ```
- **Health:** `curl localhost:8083/actuator/health`

### Ask about a folder

Any folder works: inside the files MCP server's allowed roots it opens directly, otherwise after the approval popup.
Two terminals:

```bash
# 1) files MCP server (optional DEVMATE_FILESYSTEM_ALLOWED_ROOTS=$HOME/projects pre-allows folders without the popup)
java -jar devmate-files-mcp/target/devmate-files-mcp-0.1.0-SNAPSHOT.jar      # → 127.0.0.1:8091/mcp

# 2) DevMate (Ollama example; Anthropic needs ANTHROPIC_API_KEY)
java -jar devmate-client/target/devmate-client-0.1.0-SNAPSHOT.jar
```

In the UI: **📁 Open a folder** → click `projects` → `shop` → **Open this folder** (or **Type a path instead**) → ask
"Where is Kafka used in this project?". Expand **Tools used** under the answer to see what the model searched and
read. **Close folder** goes back to plain chat. API:

```bash
curl -s -X POST localhost:8083/api/folder/ask -H 'Content-Type: application/json' \
  -d '{"folder":"/home/me/projects/shop","question":"Where is Kafka used?"}' | jq '{reply, toolCalls}'
```

Status codes: 200 answer · 400 bad input/relative path · 403 outside the allowed roots · 404 missing folder ·
502 model failed · 503 files MCP server unreachable.

### The folder picker

Browsers never give a page the absolute path of a folder you pick, so DevMate (which runs on your machine) lists
folders itself: `ui/FolderBrowser` returns only **subfolder names** of one absolute directory (no files, no contents,
no `.hidden` folders, at most 500), and `GET /folder?browse=<dir>` renders them with a breadcrumb and ⬆ parent link.
**Open this folder** posts the path to `POST /folder`, the same as typing it, so the files MCP sandbox and the
approval popup decide access exactly as before. Seeing a folder's name grants nothing. It starts at
`devmate.ui.browse-start` (`DEVMATE_BROWSE_START`, default your home folder); a folder that can't be listed shows why
and falls back there.

### A folder outside the allowed roots: the approval popup

With the files MCP server's default `DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=true`, opening any folder outside its
roots shows **"Allow DevMate to read this folder?"** (read-only, secrets stay hidden, where the content goes, until
DevMate restarts). **Allow** → DevMate declares the folder as an MCP root (`roots` capability, `roots/list_changed`)
and the server lets DevMate's connection read it. **Cancel** → nothing changes.

- Only the popup can approve (a same-site form POST with your session). `/api/folder/ask` can't approve; it can use
  a folder you already approved.
- Approvals are in memory in DevMate, shared by its browser sessions (DevMate has one MCP connection and is
  single-user). They're re-declared after a server restart and gone after a DevMate restart.
- If the server runs with the setting off (`false`), Allow says so ("did not accept the approval. Start it with
  DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=true") and the approval is withdrawn.

Small local models are slow with tools on CPU (llama3.2:3b took about 60 s for one search + answer) and sometimes
skip the tool. Naming the tool in the question helps ("…use search_code"). Claude uses tools reliably.

---

## Test

**Do the messages I send from the UI work? One command:**

```bash
./scripts/check-client.sh          # 89 example messages/actions → "ALL 89 CASES PASSED"
LIVE=1 ./scripts/check-client.sh   # + 4 against your real Ollama (incl. a folder question)
```

The example messages and expected results are in
[docs/testing/UI-chat-test-cases.md](../docs/testing/UI-chat-test-cases.md). Try them by hand in the UI too.

Maven suites:

```bash
./mvnw -pl devmate-client verify                       # all client tests (70 unit + 54 IT)
./mvnw -pl devmate-client test -Dtest=MarkdownRendererTest
./mvnw -pl devmate-client verify -Dit.test=ChatPageIT
DEVMATE_LIVE_OLLAMA=true ./mvnw -pl devmate-client verify -Dit.test=OllamaLiveIT   # real Ollama call
```

| Test | Proves |
|---|---|
| `ChatPageIT` | UI over real HTTP with a cookie-keeping client: send → answer shown, history per session, clear, HTML escaped, Markdown rendered, validation, error display, `SameSite=Strict`, no `jsessionid` in URLs |
| `ChatControllerIT` | `/api/chat` returns 200 / 400 / 502 with the right JSON |
| `LlmProviderSwitchIT` | Anthropic config gives `AnthropicChatModel`, Ollama config gives `OllamaChatModel`; env model override works; missing key stops startup |
| `SpringAiLlmClientTest` | Prompt order, blank system prompt dropped, token mapping, provider errors become `LlmException`; tool loop: results sent back, tokens summed, defaults kept, unknown tool, round limit |
| `FolderChatIT` | Folder bar, picker roots, folder browsing (home start, breadcrumb, hidden/files not listed, fallback on errors), open/refuse/close, answers with "Tools used", per-session folder, MCP outage, `/api/folder/ask` 200/400/403/404/502/503; approval popup only for "outside the allowed roots", Allow opens / explains the server setting |
| `McpToolClientIT` | Real Streamable HTTP against an SDK MCP server on embedded Tomcat: tools/list + readOnly hint, tools/call, tool errors, unknown tool, server down, reconnect after restart; approved roots visible to the server via roots/list, before/after connect, after reconnect, revoke |
| `FolderAssistantTest` | Only read-only tools with `path` offered; arguments pinned to the folder; output cut off; server reasons passed through |
| `FolderScopeTest` | `..`, `/etc/passwd`, sibling folders with the same prefix refused; relative paths resolved |
| `MarkdownRendererTest` | Formatting renders; `<script>`, `<img onerror>`, `<iframe>`, `javascript:` neutralised |
| `BrowserLauncherTest` | Right command per OS; nothing on headless Linux |
| `ActuatorHealthIT` | Health/probes UP; `env`, `beans`, `heapdump` are 404 |
| `RequestIdFilterTest` | Valid ids reused; malformed ids replaced (no log injection) |
| `OllamaLiveIT` | Opt-in real round trip to local Ollama |

Normal tests never call a real model: they replace `LlmClient` with `@MockitoBean`, and a dummy Anthropic key in
`src/test/resources/application.properties` lets the context start offline. Browser auto-open is disabled there too.

**Manual check:** start the app, the browser opens, ask "List 3 causes of Kafka consumer lag". You should get a
rendered numbered list with tokens/time underneath. Then ask it to reply with `<script>alert(1)</script>`: you see
the text and no popup.

More: [developer guide](../docs/DEVELOPER-GUIDE.md) · [ADR-0003 (LLM design)](../docs/decisions/ADR-0003-model-agnostic-llm.md)
