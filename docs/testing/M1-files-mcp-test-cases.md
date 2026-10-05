# M1 test cases: Files MCP server

Acceptance cases that prove `devmate-files-mcp` works end to end, run against the **real server jar** over the
**real MCP protocol**. Every case below is automated in [`scripts/check-files-mcp.sh`](../../scripts/check-files-mcp.sh).

```bash
./scripts/check-files-mcp.sh          # → "ALL 35 CASES PASSED — the Files MCP server is working."
KEEP=1 ./scripts/check-files-mcp.sh   # same, then leave the server running for manual exploration
```

Last run: **2026-10-05, 35/35 PASS** (JDK 25.0.3, Linux).
Negative check: with `DEVMATE_FILES_MCP_ADDRESS=0.0.0.0` the script correctly **fails** TC-03 and TC-33.

## Fixture

The script builds a throw-away project and starts the server with only that folder allowed:

```text
project/                                ← the only allowed root
├── README.md
├── .env                                ← secret: must be invisible
├── src/main/java/com/acme/
│   ├── PaymentProducer.java            KafkaTemplate (line 4)
│   ├── PaymentService.java             kafkatemplate (lower case)
│   └── KafkaConfig.java                KafkaTemplate
├── src/main/resources/application.yml  spring.kafka…
├── target/Generated.java               ← build output: must be invisible (contains KafkaTemplate)
├── logo.png                            ← binary (NUL bytes)
├── big.txt                             ← 1.1 MB (> 1 MB limit)
└── escape.txt → ../outside/secret.txt  ← symlink out of the sandbox
outside/secret.txt                      ← must never be readable
```

## Cases

### Startup & configuration

| ID | Case | Steps | Expected |
|---|---|---|---|
| TC-01 | No roots configured | Start with `DEVMATE_FILESYSTEM_ALLOWED_ROOTS=""` | Exits non-zero; log says `set devmate.filesystem.allowed-roots` |
| TC-02 | Server starts | Start with root = `project/` | `GET /actuator/health` → `{"status":"UP"}` |
| TC-03 | Loopback only | `ss -ltn` | Listening on `127.0.0.1:<port>`, not `0.0.0.0`/`*` |

### MCP protocol

| ID | Case | Request | Expected |
|---|---|---|---|
| TC-04 | Initialize | `initialize` | `serverInfo.name = devmate-files`, `capabilities.tools` present, `Mcp-Session-Id` header |
| TC-05 | Finish handshake | `notifications/initialized` | HTTP 202 |
| TC-06 | Tool discovery | `tools/list` | Exactly `list_files`, `read_file`, `search_code` |
| TC-07 | Tool hints | `tools/list` | Every tool `readOnlyHint=true`, `destructiveHint=false` |
| TC-08 | Tool schema | `tools/list` | `search_code` requires `query`; `path`, `glob` optional |
| TC-09 | Unknown tool | `tools/call delete_everything` | JSON-RPC `error`; nothing executed |
| TC-10 | Missing session | `tools/list` without `Mcp-Session-Id` | HTTP 4xx |

### Tools: expected behaviour

| ID | Case | Call | Expected |
|---|---|---|---|
| TC-11 | List roots | `list_files {}` | One entry: the real path of `project/` |
| TC-12 | List a directory | `list_files {path: project}` | `src` first; `.env` and `target` absent |
| TC-13 | Read a file | `read_file {path: src/main/java/com/acme/KafkaConfig.java}` | Content contains `@Bean KafkaTemplate` |
| TC-14 | Search | `search_code {query: KafkaTemplate}` | Files: `KafkaConfig.java`, `PaymentProducer.java`, `PaymentService.java`, nothing else |
| TC-15 | Match detail | same | `PaymentProducer.java` match is `line 4`, text `private final KafkaTemplate<String, String> template;` |
| TC-16 | Glob filter | `search_code {query: kafka, glob: *.yml}` | Only `application.yml` |
| TC-17 | Literal, not regex | `search_code {query: a.*b}` | 0 matches (no ReDoS surface) |

### Tools: security (every one must be refused)

Each must return `isError: true` with text starting `REASON:`, and must never include the outside path or secret content.

| ID | Attack / misuse | Call | Expected reason |
|---|---|---|---|
| TC-18 | `../` traversal | `read_file ../outside/secret.txt` | `ACCESS_DENIED` |
| TC-19 | Absolute path outside | `read_file /etc/passwd` | `ACCESS_DENIED` |
| TC-20 | Symlink escape | `read_file escape.txt` | `ACCESS_DENIED` |
| TC-21 | Secret file | `read_file .env` | `ACCESS_DENIED` |
| TC-22 | Excluded directory | `read_file target/Generated.java` | `ACCESS_DENIED` |
| TC-23 | Probing for existence | `read_file /no/such/dir` | `ACCESS_DENIED` (same as an existing outside path) |
| TC-24 | Binary file | `read_file logo.png` | `BINARY_FILE` |
| TC-25 | Huge file | `read_file big.txt` | `TOO_LARGE` |
| TC-26 | Missing file | `read_file nope.txt` | `NOT_FOUND` |
| TC-27 | Directory as file | `read_file src` | `NOT_A_FILE` |
| TC-28 | Blank query | `search_code " "` | `INVALID_ARGUMENT` |
| TC-29 | Search outside | `search_code {path: ../outside}` | `ACCESS_DENIED` |
| TC-30 | Search leakage | `search_code KafkaTemplate` | No result from `.env`, `target/`, `big.txt`, `logo.png`, `escape.txt`, `outside/` |

### Transport security & logging

| ID | Case | Steps | Expected |
|---|---|---|---|
| TC-31 | Foreign browser origin | POST with `Origin: http://evil.example` | HTTP 403 |
| TC-32 | Allow-listed origin | POST `initialize` with `Origin: http://localhost:6274` | HTTP 200 |
| TC-33 | Network exposure | `GET http://<LAN-IP>:<port>/actuator/health` | Connection refused |
| TC-34 | No content in logs | grep server log for file contents | No matches |
| TC-35 | Observability | grep server log | `[…,search_code] … tool call succeeded durationMs=…` |

## Relation to the automated test suite

These cases check the **packaged jar as a user runs it**. The Maven suites check the same behaviour at lower levels:
`FileAccessPolicyTest`, `FileServiceTest`, `FileToolsTest`, `FilesPropertiesTest` (unit) and `FilesMcpServerIT`
(official MCP SDK client). Both must pass before M1 is considered working:

```bash
./mvnw -pl devmate-files-mcp verify && ./scripts/check-files-mcp.sh
```
