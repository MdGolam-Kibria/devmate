# M2 test cases: MCP client

Acceptance cases that prove DevMate's MCP client works end to end **without an LLM**: connect → initialize → discover
tools → register them → execute → result, and every failure category of the brief. They run against the **packaged
jars** over the **real MCP protocol**, through DevMate's `/api/mcp` API. Every case is automated in
[`scripts/check-mcp-client.sh`](../../scripts/check-mcp-client.sh).

```bash
./scripts/check-mcp-client.sh          # → "ALL 42 CASES PASSED — DevMate's MCP client connects, discovers, …"
KEEP=1 ./scripts/check-mcp-client.sh   # same, then leave DevMate, files MCP and fake MCP running
```

Last run: **2026-10-10, 42/42 PASS** (JDK 25.0.4, Linux).
Negative checks (2026-10-10): with `DEVMATE_ADDRESS=0.0.0.0` the script **fails** TC-37. With the read-only policy and
the client-side schema check removed from `ToolExecutor`, it **fails** TC-16 (bad arguments reach the server), TC-29
(the destructive tool is called) and TC-39. With `DEVMATE_ALLOWED_HOSTS` including the attacker's domain, it **fails**
TC-42.

## Fixture

| Process | What | Why |
|---|---|---|
| `devmate-files-mcp` jar | Real files MCP server, allowed root = the generated `ws/` folder below | The real thing: DoD `search_code("Kafka")`, sandbox still enforced |
| [`fake_mcp.py`](../../scripts/support/fake_mcp.py) | Minimal Streamable HTTP MCP server (stdlib Python) with misbehaving tools | Failures the real server never produces: slow, JSON-RPC errors, HTTP 500, 5 MB answer, expired session, a destructive tool, a clashing tool name |
| DevMate A | `devmate-client` jar with `files` (yml, priority 10) + `fake` (command line, request-timeout 1 s, max-response-size 1 MB), metrics exposed | Most cases |
| DevMate B | `files` + `down` (a port nothing listens on) | Outage cases |

No LLM: `OLLAMA_BASE_URL` points at a closed port.

```text
ws/shop/
├── src/main/java/shop/PaymentProducer.java   KafkaTemplate
├── src/main/java/shop/PaymentService.java    "publishes to Kafka"
├── src/main/java/shop/KafkaConfig.java       "Kafka producer factory"
├── target/Generated.java                     ← build output: never searched
├── .env                                      ← secret (hunter2-TOPSECRET): denied
└── notes.txt                                 TOPSECRET-CONTENT (must never reach the log)
```

Fake server tools: `fake_echo {message: string, required}`, `fake_slow` (3 s), `fake_crash` (JSON-RPC -32603, multi-line
ANSI message), `fake_strict` (-32602), `fake_http500`, `fake_big` (5 MB), `fake_delete` (`readOnlyHint=false`), and
`search_code` (same name as the files tool). `GET /__calls` counts calls and initializes; `GET /__expire` drops sessions.

## Cases

### Startup & configuration

| ID | Case | Steps | Expected |
|---|---|---|---|
| TC-01 | Starts with two servers | Start DevMate A | `/actuator/health` UP |
| TC-02 | Startup discovery | Wait for the background discovery | Log: `MCP connected server=files … serverInfo=devmate-files … protocol=20…`, `MCP tools discovered server=files tools=N`, `MCP startup discovery: servers=2 tools=N unavailable=[]` |
| TC-03 | Non-http(s) URL | `--devmate.mcp.servers.files.url=file:///etc/passwd` | Process exits; log `MCP server url must be an absolute http(s) URL` |
| TC-04 | Credentials in URL | `…url=http://admin:s3cr3tpw@127.0.0.1:1/mcp` | Process exits; `must not carry credentials`; `s3cr3tpw` not in the log |
| TC-05 | Invalid server name | `--devmate.mcp.servers.Bad_Name.url=…` | Process exits; `is not a valid server name` |

### Protocol / API

| ID | Case | Steps | Expected |
|---|---|---|---|
| TC-06 | Server status | `GET /api/mcp/servers` | `files`: `CONNECTED`, `serverName=devmate-files`, `protocolVersion` from initialize, `tools ≥ 6`, no error; `fake`: `CONNECTED`, `fake-mcp` |
| TC-07 | Discovered tools | `GET /api/mcp/tools` | files tools incl. `list_files`, `read_file`, `search_code`, each with `server`, `readOnly=true`, an object `inputSchema` |
| TC-08 | Not hardcoded | same | The fake server's tools (`fake_echo`, `fake_slow`, `fake_delete`, …) appear too |
| TC-09 | Name clash | same | `search_code` listed once, for `files` (priority 10 beats the fake's default 100) |
| TC-10 | **DoD** | `POST /api/mcp/tools/search_code/call {"arguments":{"query":"Kafka"}}` | 200, `server=files`, `error=false`; `PaymentProducer.java`, `PaymentService.java`, `KafkaConfig.java`; not `Generated.java`; fake's `search_code` never called |
| TC-11 | Read a file | `read_file {path: …/KafkaConfig.java}` | 200 with the content |
| TC-12 | Registry cache | `GET /api/mcp/tools` twice, then `?refresh=true` | Only the refresh re-runs `tools/list` (log count) |
| TC-13 | Routing | `fake_echo {message: hi}` | 200, `server=fake`, `echo: hi` |

### Expected behaviour: error handling

| ID | Case | Steps | Expected |
|---|---|---|---|
| TC-14 | Tool error | `read_file {path: …/nope.txt}` | 200, `error=true`, `errorCode=TOOL_ERROR`, text `NOT_FOUND: …` (server's reason kept) |
| TC-15 | Unknown tool | `no_such_tool` | 404 `UNKNOWN_TOOL` |
| TC-16 | Invalid arguments (client) | `search_code {}`; `fake_echo {message: 42}` | 400 `INVALID_ARGUMENTS` both; the fake server received no extra `fake_echo` call |
| TC-17 | Invalid arguments (server) | `fake_strict` (-32602) | 400 `INVALID_ARGUMENTS`, `depth must be <= 8` |
| TC-18 | Server error | `fake_crash` (-32603) | 502 `SERVER_ERROR`, `(JSON-RPC -32603)` |
| TC-19 | Timeout | `fake_slow` (3 s) with a 1 s request timeout | 504 `TIMEOUT` in < 2.5 s; called once (not retried) |
| TC-20 | Session survives a timeout | `fake_echo` right after | 200; no new `initialize` |
| TC-21 | Transport error | `fake_http500` | 503 `MCP_UNAVAILABLE`, `code=TRANSPORT_ERROR`, `server=fake` |
| TC-22 | Oversized answer | `fake_big` (5 MB > the fake's 1 MB cap) | 503 `TRANSPORT_ERROR`, `larger than max-response-size`; called once; DevMate still UP |
| TC-23 | Session expired | `GET fake/__expire`, then `fake_echo` | 200 on a new session (initialize count +1) |
| TC-24 | Connection failure | Stop the files MCP jar; `read_file` | 503 `CONNECTION_FAILED`, `Files MCP server is not reachable at …`, in < 5 s |
| TC-25 | Reconnect | Restart the files MCP jar; `search_code Kafka` | 200 with matches, DevMate not restarted |
| TC-26 | Server down at startup | DevMate B; `GET /api/mcp/servers` | DevMate started; `down`: `UNAVAILABLE`, `CONNECTION_FAILED`, `connection refused` |
| TC-27 | Down server doesn't hide others | DevMate B: `search_code Kafka` | 200 with matches |
| TC-28 | Unknown tool while a server is down | DevMate B: `git_log` | 503 for `server=down` (the tool may be that server's), not 404 |

### Security / misuse

| ID | Case | Steps | Expected |
|---|---|---|---|
| TC-29 | Not read-only | `fake_delete` (`readOnlyHint=false`) | 403 `TOOL_NOT_ALLOWED`; the fake server never received it |
| TC-30 | Sandbox kept | `read_file {path: /etc/passwd}` | `TOOL_ERROR`, `ACCESS_DENIED…`; no `root:` |
| TC-31 | Traversal | `read_file {path: ../../../../../../etc/passwd}` | `ACCESS_DENIED` |
| TC-32 | Secret file | `read_file {path: …/.env}` | `ACCESS_DENIED`; `hunter2` never returned |
| TC-33 | Bad tool names | `bad%20name`, 129 × `a` | 400 `INVALID_REQUEST` |
| TC-34 | No CSRF / drive-by | form-urlencoded POST, `text/plain` POST, GET | 415, 415, 405 |
| TC-35 | Malformed JSON | body `{"arguments":` | 400 |
| TC-36 | Server text sanitised | `fake_crash` (message with `\n` and ANSI escape) | Returned text has neither |
| TC-42 | DNS rebinding | POST with `Host: rebind.evil.example`; GET with `Host: localhost:<port>` | 403 `HOST_NOT_ALLOWED`, no file content; localhost 200 |

### Transport security & logging

| ID | Case | Steps | Expected |
|---|---|---|---|
| TC-37 | Network exposure | `GET http://<LAN-IP>:<port>/api/mcp/servers` | Connection refused |
| TC-38 | No arguments/results in logs | Call with `query=hunter2-query-secret`; read `notes.txt`; grep the DevMate log | No `hunter2-query-secret`, `TOPSECRET-CONTENT`, `KafkaTemplate<` |
| TC-39 | Call log | grep the DevMate log | `MCP tools/call server=files tool=search_code outcome=SUCCESS durationMs=…`, `… fake_slow outcome=TIMEOUT`, `… fake_delete outcome=TOOL_NOT_ALLOWED` |
| TC-40 | Metric (opt-in) | `GET /actuator/metrics/devmate.mcp.client.calls` (DevMate A exposes metrics) | Tags `server`, `tool`, `outcome` |
| TC-41 | Metrics hidden by default | `GET /actuator/metrics` on DevMate B | 404 |

## Relation to the automated test suite

These cases check the **packaged jars as a user runs them**. The Maven suites check the same behaviour at lower levels:
`ToolExecutorTest`, `ToolRegistryTest`, `McpConnectionTest`, `McpClientPropertiesTest`, `HostHeaderFilterTest` (unit),
`McpClientServiceIT` (every category against official-SDK servers on embedded Tomcat) and `McpControllerIT` (the HTTP
API through the whole application). Both must pass before M2 is considered working:

```bash
./mvnw -pl devmate-client verify && ./scripts/check-mcp-client.sh
```
