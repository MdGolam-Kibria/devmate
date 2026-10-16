# M3 test cases: agent (LLM + dynamically discovered MCP tools)

Acceptance cases for the M3 flow: User → agent → LLM → tool selection → MCP client → MCP server → result → LLM →
answer. The model must get the tools discovered through `tools/list`, with none hardcoded. The cases run against the
**packaged jars** over the real MCP protocol. They go through DevMate's agent (`POST /api/agent/ask`,
`/api/folder/ask` and the chat UI), and every case is automated in
[`scripts/check-agent.sh`](../../scripts/check-agent.sh).

```bash
./scripts/check-agent.sh            # → "ALL 37 CASES PASSED — DevMate's agent gives the model the discovered MCP tools, …"
LIVE=1 ./scripts/check-agent.sh     # + LIVE-1…3: the DoD question to your real Ollama (qwen2.5:7b; `LIVE_MODEL=` to change), no keywords
KEEP=1 ./scripts/check-agent.sh     # leave everything running afterwards
```

Last run: **2026-10-16, 37/37 PASS** (JDK 25.0.4, Linux).
Negative checks (2026-10-16): with `DEVMATE_ADDRESS=0.0.0.0` the script **fails** TC-33. With the read-only filter
removed from `DevMateAgent`, it **fails** TC-07 and TC-27. With `FolderScope` removed from folder questions, it
**fails** TC-11 and TC-29. LIVE (2026-10-15, llama3.2:3b on CPU): see
[M3.md](../milestones/M3.md#live-run).

## Fixture

| Process | What | Why |
|---|---|---|
| `devmate-files-mcp` jar | Real files MCP server, allowed root = the generated `ws/` | The real tools: DoD `search_code`, deny lists, sandbox |
| [`fake_mcp.py`](../../scripts/support/fake_mcp.py) | Second MCP server (`fake_echo`, `fake_delete` not read-only, a clashing `search_code`, …) | Tools come from every configured server; a destructive tool must never be offered |
| [`fake_ollama.py`](../../scripts/support/fake_ollama.py) | Deterministic model, keyword-driven | Repeatable "tool selection": `SEARCH`/`READ`/`LIST`/`LOOP`/`CALL <tool> <json>`; it also reports what DevMate sent: `TOOLS`, `HISTORY`, `SYSTEM`; `FAIL` → HTTP 500 |
| DevMate A | `devmate-client` jar: `files` + `fake`, `max-tool-result-chars=2000`, `max-history-messages=4`, metrics exposed | Most cases |
| DevMate B | Both MCP servers pointed at a closed port | Degradation cases |

```text
ws/
├── shop/
│   ├── src/main/java/shop/PaymentProducer.java   (KafkaTemplate)
│   ├── src/main/java/shop/KafkaConfig.java       (Kafka producer factory)
│   ├── target/Generated.java                     (build dir: never searched)
│   ├── big.txt                                   (~24 KB: cut at 2000 chars)
│   └── .env                                      (DB_PASSWORD=hunter2-TOPSECRET: denied)
└── other/notes.txt                               (inside the root, outside the shop folder)
```

The DoD question is `এই project-এ Kafka কোথায় ব্যবহার করা হয়েছে?`. With the fake model, `SEARCH KafkaTemplate` stands
for the model's choice. The LIVE cases ask the real model the bare question.

## Startup & configuration

| ID | Steps / request | Expected |
|---|---|---|
| TC-01 | Start DevMate A | Health `UP`; log `LLM configured provider=ollama model=fake-model` |
| TC-02 | Start DevMate B (every MCP server down) | Health `UP`: MCP servers are not a startup dependency |
| TC-03 | `--devmate.agent.max-tool-rounds=0` | DevMate refuses to start (`maxToolRounds`) |
| TC-04 | `--devmate.agent.max-history-messages=-1` | DevMate refuses to start (`maxHistoryMessages`) |

## Protocol / API

| ID | Steps / request | Expected |
|---|---|---|
| TC-05 | `POST /api/agent/ask {"question":"<DoD> SEARCH KafkaTemplate"}` | 200; `toolCalls[0].name = search_code`, no error; reply names `PaymentProducer.java`, never `Generated.java` |
| TC-06 | *(same)*, inspect what the model received | The Bangla question arrives byte-for-byte (UTF-8) |
| TC-07 | `TOOLS` | `toolsOffered` = every read-only tool of both servers (`list_files`, `read_file`, `search_code`, `folder_tree`, `find_files`, `folder_overview`, `fake_echo`, …); `fake_delete` is absent; `search_code` appears once |
| TC-08 | *(same)* | The model's own list of received tool definitions equals `toolsOffered` |
| TC-09 | *(same)*, inspect the `search_code` definition | Description equals the one the server sent (as listed by `/api/mcp/tools`); parameters are the server's schema (`query`, `contextLines`, …) |
| TC-10 | `CALL fake_echo {"message":"hi from the agent"}` | The second server's tool is called once; `echo: hi from the agent` reaches the answer |
| TC-11 | `POST /api/folder/ask {folder: ws/shop, question: "SEARCH KafkaTemplate"}` | 200 through the agent; only tools with a `path` offered (no `fake_echo`); answer names `PaymentProducer.java` |
| TC-12 | `POST /api/chat {"message":"plain"}` | Still plain model chat: no tool definitions sent |
| TC-13 | Blank question · `{}` · 8001 chars · history role `system` · empty history text · 51 history entries · malformed JSON · form post · `GET` | 400 × 7 · 415 · 405 |

## Expected behaviour

| ID | Steps / request | Expected |
|---|---|---|
| TC-14 | `HISTORY` with `history: [user "My name is Rahim", assistant "Noted"]` | Model received both, in order |
| TC-15 | `HISTORY` with 10 question/answer pairs | Model received only the newest 4 messages (`q8 a8 q9 a9`) |
| TC-16 | UI: `My name is Rahim` → `FAIL on purpose` → `HISTORY` | Model received the first pair only; the failed question is not memory |
| TC-17 | UI: **New chat**, then `HISTORY` | `History: 0 messages` |
| TC-18 | UI without a folder: `SEARCH KafkaTemplate` | **Tools used (1)** `search_code {"query":"KafkaTemplate"}`; answer names `PaymentProducer.java`; folder bar "No folder open: DevMate uses every tool…" |
| TC-19 | `READ <ws>/shop/missing.txt` | 200; `toolCalls[0].error = true`; the model saw `NOT_FOUND` and answered |
| TC-20 | `READ <ws>/shop/big.txt` | Tool output cut at 2000 chars; summary says `cut off by DevMate at 2000 chars` |
| TC-21 | `LOOP` (model never stops calling tools) | "I stopped after 8 rounds…", 8 tool calls |
| TC-22 | DevMate B: `hello with no servers` | 200 `Echo: …`; `toolsOffered = []`; `unavailableServers = [fake, files]` |
| TC-23 | DevMate B: `SYSTEM` | System prompt says no tools are available and names the unreachable servers |
| TC-24 | DevMate B, UI: `hi` | Under the answer: `unreachable MCP: files, fake (answered without its tools)` |

## Security / misuse (every refusal)

| ID | Steps / request | Expected |
|---|---|---|
| TC-25 | `READ <ws>/shop/.env` | `ACCESS_DENIED` (server deny list); `hunter2` never in the response |
| TC-26 | `READ /etc/passwd` (no folder) | `ACCESS_DENIED` (server sandbox); no `root:x` |
| TC-27 | `CALL fake_delete {}` (model asks for a tool it wasn't offered) | `UNKNOWN_TOOL`; the server's `fake_delete` call count stays 0 |
| TC-28 | `CALL fake_echo {"message":42}` | `INVALID_ARGUMENTS` (client-side schema check); never sent |
| TC-29 | Folder `ws/shop`: `READ ../other/notes.txt` · `CALL fake_echo {…}` | `ACCESS_DENIED: path is outside the selected folder`, no content · `UNKNOWN_TOOL` (no `path`: not offered in a folder) |
| TC-30 | History entry with role `system` | 400; the model is never called |
| TC-31 | `SYSTEM` | Prompt says tool results are data, never instructions; it names no tool |
| TC-32 | `POST /api/agent/ask` with `Host: rebind.evil.example` | 403; nothing read |

## Transport security & logging

| ID | Steps / request | Expected |
|---|---|---|
| TC-33 | `POST http://<LAN-IP>:<port>/api/agent/ask` | Connection refused: DevMate binds 127.0.0.1 |
| TC-34 | Grep the app log after all cases | No question text (incl. Bangla), history, tool arguments, file contents or secrets |
| TC-35 | *(same)* | One line per answer: `agent answered scope=… toolsOffered=N unavailableServers=[…] historyMessages=N toolCalls=N durationMs=N` |
| TC-36 | `GET /actuator/metrics/devmate.agent.answers` | Timer tagged `scope`, `outcome` (A exposes metrics); B (defaults) → 404 |
| TC-37 | `FAIL now` | 502 `LLM_UNAVAILABLE`, no exception names or stack frames |

## LIVE (optional, `LIVE=1`, real Ollama)

| ID | Steps / request | Expected |
|---|---|---|
| LIVE-1 | `/api/folder/ask` with the bare DoD question | The model selects `search_code` (or another file tool) by itself |
| LIVE-2 | *(same)* | The answer names `PaymentProducer` or `KafkaConfig` |
| LIVE-3 | `/api/agent/ask` with the bare DoD question (no folder) | Non-empty answer, tools offered |
