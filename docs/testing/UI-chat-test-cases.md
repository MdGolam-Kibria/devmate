# Web UI test cases: chat messages

Example messages to send from the DevMate web UI (`http://localhost:8083/`) and what must happen for each. Every
case is automated in [`scripts/check-client.sh`](../../scripts/check-client.sh), which drives the **real app jar**
exactly like a browser: form POST, redirect, session cookie, rendered HTML.

```bash
./scripts/check-client.sh            # 89 deterministic cases against a fake model (~60 s)
LIVE=1 ./scripts/check-client.sh     # + 4 cases against your real Ollama (qwen2.5:7b), a few minutes on CPU
KEEP=1 ./scripts/check-client.sh     # leave the app (and files MCP server) running on the fake model
```

Last run: **2026-10-16: 89/89 PASS** (JDK 25; M3: UI-35/UI-59 now expect the general agent). Earlier: **2026-10-09: 89/89 PASS**. Negative check (2026-10-09): with the tool outcome left out of the
"Tools used" lines, UI-82 and UI-83 **fail**. The folder Q&A cases also passed with `LIVE=1` (64/64, before the
approval cases were added).
Negative checks: with `DEVMATE_ADDRESS=0.0.0.0 DEVMATE_LLM_MAX_ATTEMPTS=6` the script correctly **fails** UI-34
(reachable from the LAN) and UI-23 (error took 23 s). With the folder-scope check in `FolderScope` disabled it
correctly **fails** UI-49 (read `../other/notes.txt`) and UI-50 (read `/etc/passwd`). With the files MCP server
forced to always accept client roots, it correctly **fails** UI-64 (approval must need the server setting). With the
folder browser listing files and hidden folders too, it correctly **fails** UI-72.

## Why a fake model?

Real models answer differently every time, so a check like "the reply contains a table" can't be reliable. The script
points DevMate at `scripts/support/fake_ollama.py`, which speaks Ollama's `/api/chat` and answers **by keyword**:

| Your message contains | Fake model replies |
|---|---|
| `MARKDOWN` | Bold text, a numbered list, `inline code`, a Java code block and a table |
| `XSS` | `<script>alert('xss')</script>`, a `javascript:` link and `<img onerror>` (DevMate must neutralise all of it) |
| `FAIL` | HTTP 500 (provider error) |
| anything else | `Echo: <your message>` |

With a folder open DevMate also sends tool definitions; then these keywords make the fake model **call a tool**,
and once the result is back it replies `From the tools:` followed by the raw tool output:

| Your message contains | Fake model calls |
|---|---|
| `SEARCH <text>` | `search_code {"query": "<text>"}` |
| `READ <path>` | `read_file {"path": "<path>"}` |
| `LIST` | `list_files {}` |
| `TREE` | `folder_tree {"depth": 8}` |
| `FIND <glob>` | `find_files {"glob": "<glob>"}` |
| `OVERVIEW` | `folder_overview {}` |
| `RANGE <path> <a> <b>` | `read_file {"path": "<path>", "startLine": a, "endLine": b}` |
| `LOOP` | `list_files` on every round, forever (DevMate must stop it) |
| `SYSTEM` | nothing; replies with the system prompt it received |

The app, UI, validation, rendering, sessions and error handling under test are all real. Only the model is replaced.
The `LIVE` cases then confirm the same path works with a real model.

---

## Example messages

Type these in the UI box and press **Enter**. The "Expected (fake model)" column is what the script checks; the last
column is what to look for when you try it by hand with a real model.

### Normal messages

| ID | Message to send | Expected (fake model) | With a real model |
|---|---|---|---|
| UI-07 | `Hello` | Your bubble "Hello" on the right; reply "Echo: Hello" on the left | A greeting |
| UI-08 | *(same)* | Under the reply: `fake-model · 12 in / 7 out tokens · N ms` | `llama3.2:3b · … tokens · … ms` |
| UI-09 | `এই project-এ Kafka কোথায় ব্যবহার করা হয়েছে?` | Bangla text shown intact in both bubbles | A general answer (no folder open, so no code access) |
| UI-10 | `Price < 100 & qty > 5 😀 "quotes"` | Shown exactly as typed; `<`, `&`, `>`, `"` are escaped in HTML, emoji kept | — |
| UI-11 | three lines: `line one` ⏎ `line two` ⏎ `line three` (Shift+Enter between) | Line breaks preserved in your bubble | — |
| UI-17 | *(after the above)* | All messages still listed, oldest at the top | — |

### Formatting (Markdown)

| ID | Message to send | Expected (fake model) | With a real model |
|---|---|---|---|
| UI-12 | `MARKDOWN: list 3 causes of Kafka consumer lag` | **Bold** text, a numbered list, `max.poll.records` as code, a grey Java code block | `List 3 causes of Kafka consumer lag as a numbered list with bold titles` gives a rendered list |
| UI-13 | *(same)* | A rendered table (`Cause` / `Fix`) | `Compare Kafka and RabbitMQ in a table` |

### Folder Q&A (open a folder, ask about its code)

Fixture the script builds (the files MCP server's allowed root is `ws/`):

```text
ws/
├── project/                      ← the folder you open
│   ├── README.md
│   ├── src/main/java/demo/OrderService.java   (line 4: KafkaTemplate)
│   ├── target/Generated.java     (build dir: hidden by the server)
│   └── .env                      (DB_PASSWORD=hunter2: denied by the server)
└── other/notes.txt               (inside the allowed root, but outside the opened folder)
```

How to do it by hand: click **📁 Open a folder** in the bar under the header, type an absolute path (or click one
of the allowed roots), press **Open**, then ask. Under each answer, **Tools used (N)** lists every tool call.

| ID | Step / message | Expected |
|---|---|---|
| UI-35 | Open `/` | Folder bar says "No folder open: DevMate uses every tool of its MCP servers…" with an **Open a folder** link (M3: the general agent) |
| UI-36 | Click **Open a folder** while the files MCP server is stopped | "Files MCP server is not reachable at http://127.0.0.1:…/mcp" |
| UI-37 | Same, server running | The allowed root (`…/ws`) is offered as a clickable chip |
| UI-38 | Open `/etc` | `ACCESS_DENIED: path is outside the allowed roots`; no folder opened |
| UI-39 | Open `project` (relative) | `INVALID_ARGUMENT: enter an absolute path…`; the server isn't asked |
| UI-40 | Open `…/ws/missing` | `NOT_FOUND: no such file or directory` |
| UI-41 | Open `…/ws/project/README.md` | `NOT_A_DIRECTORY` |
| UI-42 | Open `…/ws/project` | Folder bar shows the path and **2 entries** (`.env` and `target/` are hidden) |
| UI-43 | *(same)* | Empty state "Ask about this folder" with code suggestions |
| UI-44 | `Where is Kafka used? SEARCH KafkaTemplate` | Answer contains `OrderService.java`, line 4 |
| UI-45 | *(same)* | **Tools used (1)**: `search_code {"query":"KafkaTemplate"}`; details say "1 tool call" |
| UI-46 | *(same)* | `target/Generated.java` never appears (build dirs excluded) |
| UI-47 | `READ src/main/java/demo/OrderService.java` | The file content (`class OrderService`), path resolved against the folder |
| UI-53 | `এই project-এ Kafka কোথায় ব্যবহার করা হয়েছে? SEARCH KafkaTemplate` | Bangla question intact; answer from the tool |
| UI-52 | `SYSTEM` | The system prompt names the folder and lists `src/`, `README.md`; never `.env` |
| UI-54 | Open `/` in a second browser | No folder open there (the folder is per session) |
| UI-55 | `POST /api/folder/ask {"folder": "…/project", "question": "SEARCH KafkaTemplate"}` | 200 with `reply` and `toolCalls[0].name = search_code` |
| UI-59 | Click **Close folder**, send `hello after close` | Back to the general agent (tools no longer pinned to the folder): `Echo: hello after close` |

Security / misuse: the model must not get out of the folder, and the server's rules still apply:

| ID | Step / message | Expected |
|---|---|---|
| UI-48 | `READ .env` | `ACCESS_DENIED: path is excluded by policy` (server deny list); `hunter2` never shown |
| UI-49 | `READ ../other/notes.txt` | `ACCESS_DENIED: path is outside the selected folder` (DevMate's folder scope); "private notes" never shown |
| UI-50 | `READ /etc/passwd` | `ACCESS_DENIED: path is outside the selected folder`; nothing read |
| UI-51 | `LOOP` (model never stops calling tools) | Stopped: "I stopped after 8 rounds of tool calls…", **Tools used (8)** |
| UI-56 | API: folder `/etc` · `…/ws/missing` · `project` · blank question | 403 · 404 · 400 · 400 |

Resilience and logging:

| ID | Step / message | Expected |
|---|---|---|
| UI-57 | Stop the files MCP server, ask `SEARCH KafkaTemplate` | Red error "Files MCP server is not reachable…", within 10 s |
| UI-58 | Start it again, ask again | Works again without restarting DevMate (new MCP session) |
| UI-60 | Read `app.log` and `mcp.log` after all of the above | No file contents, secrets or tool arguments are logged (only tool names, outcome, latency) |

### Folder outside the allowed roots: the approval popup

Fixture: `elsewhere/` (outside the server's allowed root `ws/`) with `docs/guide.md` and `.env` (`API_KEY=sk-elsewhere`).

| ID | Step / message | Expected |
|---|---|---|
| UI-61 | Open `…/elsewhere` | Popup **"Allow DevMate to read this folder?"** with the path and **Allow** / **Cancel** |
| UI-62 | *(same)* | Popup explains: read and search only, secrets stay hidden, content stays local (Ollama), until DevMate restarts |
| UI-63 | Open `…/ws/missing` | `NOT_FOUND`, **no** popup (only "outside the allowed roots" can be approved) |
| UI-64 | **Allow**, server started *without* `DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=true` | "did not accept the approval. Start it with DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=true…"; nothing opened |
| UI-65 | Restart the server with the setting, **Allow** | The folder opens |
| UI-66 | `READ docs/guide.md` | "approved folder content" |
| UI-67 | `READ .env` | `ACCESS_DENIED: path is excluded by policy`; `sk-elsewhere` never shown |
| UI-68 | `POST /api/folder/ask` for another outside folder (`…/third`) | 403: the API can't approve |
| UI-69 | Restart the files MCP server, `READ docs/guide.md` again | Still works: DevMate declares its approved folders on the new connection |

### Folder picker: click a folder instead of typing its path

DevMate starts with `DEVMATE_BROWSE_START=…/ws`; `project/` also has a `.git/` folder. A fresh browser session is used.

| ID | Step | Expected |
|---|---|---|
| UI-70 | **📁 Open a folder** | Picker shows `…/ws` and an **Open this folder** button |
| UI-71 | *(same)* | Subfolders `other`, `project` as links, sorted |
| UI-72 | Click `project` | `src`, `target` listed; files (`README.md`, `.env`) and `.git` not |
| UI-73 | *(same)* | Breadcrumb links back to `…/ws`; `project` marked as current |
| UI-74 | *(same)* | **Open this folder** submits `…/ws/project` |
| UI-75 | Click **Open this folder** | The folder opens (folder bar shows it) |
| UI-76 | Browse `…/third` (never approved), open it | Its `docs` subfolder is listed; opening shows the approval popup |
| UI-77 | Browse `…/project/README.md` | "Not a folder"; picker falls back to the start folder |
| UI-78 | Browse `relative/path` | "Enter an absolute path" |
| UI-79 | Browse `/` | No ⬆ parent link; `etc` listed |
| UI-80 | *(same)* | **Type a path instead** still available (collapsed) |
| UI-81 | Browse `…/elsewhere` | No file content in the page or app log: browsing lists names only |

### Folder insight: tool outcomes and whole-folder tools

Each case starts from a cleared chat (`POST /chat/clear`), so the page holds only that reply.

| ID | Message (folder `…/ws/project` open) | Expected |
|---|---|---|
| UI-82 | `SEARCH KafkaTemplate` | Under **Tools used**: `→ matches=1 filesMatched=1 filesScanned=N truncated=false · N ms` |
| UI-83 | `READ .env` | The line is styled as an error: `→ error: ACCESS_DENIED: path is excluded by policy · N ms` |
| UI-84 | `TREE` | `folder_tree`: `src/ → main/ → java/ → demo/ → OrderService.java`; no `.env`, `target/`, `.git/` |
| UI-85 | `FIND *.java` | `find_files`: `OrderService.java`, never `target/Generated.java` |
| UI-86 | `OVERVIEW` | `folder_overview`: `"files":2` (`.env` not counted), project file kind `README` |
| UI-87 | `RANGE src/main/java/demo/OrderService.java 3 4` | Lines 3–4 only: `class OrderService {` but not `package demo` |
| UI-88 | `SYSTEM` | The system prompt has **Folder overview**: `2 files in 4 directories`, `README.md (README)`; never `.env` |
| UI-89 | `POST /api/folder/ask` `SEARCH KafkaTemplate` | `toolCalls[0].summary` starts `matches=1 `, `durationMs ≥ 0` |

### Safety: none of these may run code in your browser

| ID | Message to send | Expected |
|---|---|---|
| UI-14 | `<script>alert("you")</script> <b>bold?</b>` | Shown as literal text; no popup, nothing bold |
| UI-15 | `XSS please` | The model's `<script>`, `<img onerror>` and `javascript:` link appear as plain text: no popup, no clickable `javascript:` link |
| UI-16 | *(same)* | The text is still readable (escaped, not deleted) |

### Input validation: must NOT reach the model

| ID | Message to send | Expected |
|---|---|---|
| UI-18 | *(only spaces)* | Red note "Message must be 1–8000 characters."; nothing added to the chat |
| UI-19 | 8001 characters (paste a long text) | Same note; the browser box also stops at 8000 |
| UI-20 | *(both above)* | Model call counter unchanged |
| UI-21 | exactly 8000 characters | Accepted and answered |

### Errors

| ID | Message / action | Expected |
|---|---|---|
| UI-22 | `FAIL: simulate a provider error` | Red error bubble: `LLM provider 'ollama' failed: HTTP 500 …` |
| UI-23 | *(same)* | The error appears within a few seconds (retries are capped at 2), not minutes |
| UI-24 | Stop the model (`ollama stop` / fake killed), send `Hello while the model is down` | Red error bubble; your message still shown; page keeps working |
| UI-25 | Start the model again, send `Hello again` | Normal answer, no DevMate restart needed |

### Session & navigation

| ID | Action | Expected |
|---|---|---|
| UI-26 | Press F5 after a reply | Page reloads; the question is **not** sent again |
| UI-27 | Open the UI in a second browser / private window | Its own empty chat ("Ask DevMate") |
| UI-28 | Click **New chat** | Conversation cleared; empty state shown |
| UI-29 | DevTools → Application → Cookies | `JSESSIONID` is `HttpOnly`, `SameSite=Strict` |
| UI-30 | Watch the address bar after sending | Always `/`, never `;jsessionid=…` |

### Page & startup

| ID | Check | Expected |
|---|---|---|
| UI-01 | `curl localhost:8083/actuator/health` | `{"status":"UP",…}` |
| UI-02 | Open `/` | Title "DevMate"; badge shows provider / model |
| UI-03 | Empty chat | "Ask DevMate" plus 3 suggestion buttons (clicking one sends it) |
| UI-04 | Footer with Ollama | "Runs on your local Ollama; nothing leaves this machine." (Anthropic: "Messages are sent to anthropic.") |
| UI-05 | `/css/devmate.css` | 200 |
| UI-06 | Startup log | `DevMate UI ready at http://localhost:<port>/` (and the browser opens unless `DEVMATE_OPEN_BROWSER=false`) |
| UI-34 | From another machine: `http://<your-LAN-IP>:8083/` | Connection refused (bound to 127.0.0.1) |

### JSON API (same model, for scripts)

| ID | Request | Expected |
|---|---|---|
| UI-31 | `POST /api/chat {"message":"Hello API"}` | `{"reply":"Echo: Hello API","provider":"ollama","model":"fake-model","inputTokens":12,…}` |
| UI-32 | `POST /api/chat {"message":" "}` | 400 `INVALID_REQUEST` |
| UI-33 | `POST /api/chat {"message":"FAIL"}` | 502 `LLM_UNAVAILABLE` |

### LIVE: real Ollama (`LIVE=1`)

Real answers vary, so these checks are deliberately loose.

| ID | Message | Pass if |
|---|---|---|
| LIVE-1 | `What is the capital of France? Answer with one word.` | Reply mentions "Paris" |
| LIVE-2 | `List 3 causes of Kafka consumer lag as a numbered list.` | Reply renders as a list |
| LIVE-3 | `Kafka কী? দুই বাক্যে বলো।` | A non-empty answer bubble |
| LIVE-4 | Open the fixture project, ask `In which file is KafkaTemplate used? Use the search_code tool.` | **Tools used** shown and the answer names `OrderService` |

Small local models (1.5–3B) can answer factually wrong even when these pass. Quality isn't what's being tested here;
the wiring is.

---

## Found while writing these cases

- **Spring AI's default retry made provider errors hang for minutes** (10 attempts, 2 s × 5 backoff). It's now
  capped in `application.yml` (`DEVMATE_LLM_MAX_ATTEMPTS`, default 2, 1–5 s backoff), and UI-23 guards it.
- `javascript:` appearing as **text** is harmless. The check looks for it inside `href`/`src` attributes, which is
  where it would be dangerous.

## Relation to the Maven tests

`ChatPageIT`, `ChatControllerIT`, `FolderChatIT`, `McpClientServiceIT`, `MarkdownRendererTest`, `FolderScopeTest`,
`FolderAssistantTest` and `SpringAiLlmClientTest` cover the same behaviour inside a test context, with mocks or an
in-test MCP server. This script checks the **packaged app as you run it**, with a real HTTP model server.
Both must pass:

```bash
./mvnw -pl devmate-client verify && ./scripts/check-client.sh
```
