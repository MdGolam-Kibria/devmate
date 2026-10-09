# devmate-files-mcp

An **MCP server** that gives AI clients safe, **read-only** access to source files. Any MCP client (the official SDK,
MCP Inspector, Claude Desktop, and DevMate itself from M2) can discover and call its tools over
**Streamable HTTP** at **http://127.0.0.1:8091/mcp**.

---

## Features implemented (Milestone 1 🔵)

| Tool | Arguments | Returns |
|---|---|---|
| `list_files` | `path?` (omit to list the allowed roots) | entries `{name, path, type, size, modified}`, directories first |
| `read_file` | `path`, `startLine?`, `endLine?` (1-based, inclusive) | `{path, size, lineCount, startLine, endLine, truncated, content}` (UTF-8 text) |
| `search_code` | `query`, `path?`, `glob?` (e.g. `*.java`), `contextLines?` (0–5) | matches `{path, line, text, before?, after?}`, `filesMatched`, `filesScanned`, `truncated` |
| `folder_tree` | `path?`, `depth?` (default 3, max 8) | indented `tree` text (`dir/`, `dir/…` not expanded, `link@`), `directories`, `files`, `truncated` |
| `find_files` | `glob` (`*Controller.java`; with `/` it matches the relative path), `path?` | `files {name, path, type, size, modified}` sorted by path, `filesScanned`, `truncated` |
| `folder_overview` | `path?` | `files`, `directories`, `totalBytes`, top `fileTypes`, `projectFiles {path, kind}` (Maven, Gradle, npm, Docker, CI, README, Spring config, …), 10 `largestFiles`, 10 `recentlyModified`, `truncated` |

The last three answer "what is in this folder?" without reading any file: `folder_overview` says what kind of project it
is, `folder_tree` shows its layout, `find_files` locates files by name. `read_file` with a line range works on files up
to `max-range-file-size` (64 MB), so a big file is read in parts instead of being refused.

Relative paths resolve against the first allowed root. All tools are marked read-only and non-destructive in
`tools/list`, so clients can auto-approve them.

**Security built in:** no access outside configured folders, including via `..` or symlinks; secrets are never
listed, read or searched (`.env*`, `*.pem`, `*.key`, `id_rsa*`, `.netrc`, `.npmrc`, `.pgpass`, …); `.git`,
`target`, `node_modules` and similar are skipped; 1 MB file limit (64 MB for line ranges, 1 MB returned); binary files
refused; literal (non-regex) search with result and scan caps; every walk bounded and never following symlinks;
loopback-only by default; browser `Origin` allowlist; logs never contain file contents or paths.

---

## How it works

### The MCP conversation

```text
MCP client                                     devmate-files-mcp
──────────                                     ─────────────────
POST /mcp  initialize  ──────────────────────► Spring AI MCP server (MCP Java SDK 2.0.1)
◄── result {serverInfo, capabilities:{tools}}   + header Mcp-Session-Id
POST /mcp  notifications/initialized  ───────► 202
POST /mcp  tools/list  ──────────────────────► schemas generated from @McpTool method signatures
◄── [list_files, read_file, search_code, folder_tree, find_files, folder_overview]
POST /mcp  tools/call search_code {query}  ──► FileTools.searchCode(...)
◄── result {content:[{type:text, text:"{json}"}], isError:false}
```

### Inside one tool call

```text
OriginValidationFilter        browser Origin not allow-listed → 403 (DNS-rebinding protection)
        │
tools/FileTools               @McpTool adapter: the ONLY class that knows MCP
        │                     logs tool + outcome + result counts + latency, times devmate.mcp.tool.calls;
        │                     turns results/errors into CallToolResult
        ▼
service/FileService           plain Java NIO: list / read (whole or line range) / search, no MCP types
service/FolderInsights        tree / find / overview: bounded walks, names and attributes only, no contents
        │
access/FileAccessPolicy       the single security gate, used for every path:
        │                       1. parse (invalid / NUL → INVALID_ARGUMENT)
        │                       2. relative → resolve against first root; normalise
        │                       3. toRealPath() → follows symlinks to the true location
        │                       4. real path must be inside a real root   else ACCESS_DENIED
        │                       5. no excluded dir segment, no denied file name  else ACCESS_DENIED
        ▼
config/FilesProperties        validated limits; no roots and client roots off → server refuses to start
```

**Search** walks the tree without following symlinks, skips excluded directories, denied files, files over the
size limit and binary files (NUL in the first 8 KB), then does a case-insensitive *literal* match line by line. It
stops at `max-search-results` (200) or `max-search-files` (20 000) and sets `truncated=true`, so a client knows
the result may be incomplete.

**Observability.** Each call logs one line such as
`[devmate-files-mcp,search_code] tool call succeeded matches=3 filesMatched=3 filesScanned=40 truncated=false durationMs=4`:
the result's counts and flags, never arguments, paths or content. Each call is also timed as the Micrometer timer
`devmate.mcp.tool.calls` (tags `tool`, `outcome` = `success`/`rejected`/`error`, `reason`). Actuator stays
deny-by-default; to read it, start with `DEVMATE_ACTUATOR_EXPOSURE=health,info,metrics` and open
`/actuator/metrics/devmate.mcp.tool.calls?tag=tool:search_code`.

**Errors** are tool results, not protocol failures: `isError:true` with one line `REASON: detail`. Reasons:
`INVALID_ARGUMENT`, `NOT_FOUND`, `ACCESS_DENIED`, `NOT_A_FILE`, `NOT_A_DIRECTORY`, `TOO_LARGE`, `BINARY_FILE`,
`INTERNAL_ERROR`. A path outside the sandbox gives the same `ACCESS_DENIED` whether or not it exists, so the server
can't be used to probe your disk. Unexpected exceptions become `INTERNAL_ERROR: unexpected server error`, never
the real message.

---

## Run

```bash
export JAVA_HOME=~/.jdks/ms-25.0.4.1
./mvnw -pl devmate-files-mcp -am package -DskipTests      # from the repo root
DEVMATE_FILESYSTEM_ALLOWED_ROOTS=/home/golamkibria/kibria/devmate \
  java -jar devmate-files-mcp/target/devmate-files-mcp-0.1.0-SNAPSHOT.jar
```

| Setting | Env var / property | Default |
|---|---|---|
| Always-allowed folders | `DEVMATE_FILESYSTEM_ALLOWED_ROOTS` (comma-separated **absolute** paths) | none (required only when client roots are off) |
| Accept client-approved folders | `DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS` | `true` |
| Port | `DEVMATE_FILES_MCP_PORT` | `8091` |
| Listen address | `DEVMATE_FILES_MCP_ADDRESS` | `127.0.0.1` |
| Browser origins allowed | `DEVMATE_HTTP_ALLOWED_ORIGINS` | none |
| Max file size (whole read; also the most one range returns) | `devmate.filesystem.max-file-size` | `1MB` |
| Max file size for a line-range read | `devmate.filesystem.max-range-file-size` | `64MB` |
| Max search results / files scanned (also caps tree/find/overview walks) | `devmate.filesystem.max-search-results` / `max-search-files` | `200` / `20000` |
| Max context lines per match | `devmate.filesystem.max-context-lines` | `5` |
| Max tree depth / entries returned by `folder_tree` and `find_files` | `devmate.filesystem.max-tree-depth` / `max-tree-entries` | `8` / `2000` |
| Actuator endpoints exposed | `DEVMATE_ACTUATOR_EXPOSURE` | `health, info` |
| Hidden directories | `devmate.filesystem.excluded-directories` | `.git, target, build, node_modules, …` |
| Hidden files | `devmate.filesystem.denied-files` | `.env*, *.pem, *.key, id_rsa*, …` |

### Client-approved folders (MCP roots)

With `DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=true`, each tool call asks the calling client for its MCP **roots**
(`roots/list`) and adds those folders to the sandbox **for that client's session only** (`access/ClientRoots` →
`FileAccessPolicy.withAdditionalRoots`). DevMate declares the folders you approved in its popup. Everything else still
applies: real-path checks, symlinks, deny lists, hidden directories, read-only. Only `file:` roots that exist as
directories count. It's on by default so the server can start with no roots (e.g. from the IDE) and every folder is
granted through DevMate's popup. The trade-off: any local process that speaks MCP can read any folder it declares.
On a shared machine set it to `false` and list `DEVMATE_FILESYSTEM_ALLOWED_ROOTS`. Tested by `ClientRootsIT` (on) and
`FilesMcpServerIT.clientRootsAreIgnoredByDefault` (off).

## Use

**With curl** (raw MCP):

```bash
H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
curl -s -D /tmp/h "${H[@]}" localhost:8091/mcp -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
SID=$(grep -i '^mcp-session-id' /tmp/h | cut -d' ' -f2 | tr -d '\r')
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" localhost:8091/mcp -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" localhost:8091/mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" localhost:8091/mcp -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_code","arguments":{"query":"KafkaTemplate","glob":"*.java"}}}'
```

**With MCP Inspector** (needs Node.js): start the server with
`DEVMATE_HTTP_ALLOWED_ORIGINS=http://localhost:6274`, run `npx @modelcontextprotocol/inspector`, choose
*Streamable HTTP* and `http://localhost:8091/mcp`.

---

## Test

**Is it working? One command:**

```bash
./scripts/check-files-mcp.sh     # builds a fixture, starts the jar, runs 56 cases → "ALL 56 CASES PASSED"
```

The cases are listed in [docs/testing/M1-files-mcp-test-cases.md](../docs/testing/M1-files-mcp-test-cases.md).

Maven suites:

```bash
./mvnw -pl devmate-files-mcp verify                        # 84 unit + 23 integration tests
./mvnw -pl devmate-files-mcp test -Dtest=FileAccessPolicyTest
./mvnw -pl devmate-files-mcp verify -Dit.test=FilesMcpServerIT
```

| Test | Proves |
|---|---|
| `FilesMcpServerIT` | Boots the server and drives it with the **official MCP Java SDK client**: initialize (tools capability only), tools/list (6 tools, schemas, read-only hints), each tools/call incl. line ranges, context lines, tree, find and overview, `search_code("KafkaTemplate")` → `PaymentProducer`, `PaymentService`, `KafkaConfig`; escape attempts are tool errors; unknown tool is a protocol error; bad Origin 403; per-tool metric readable when exposed |
| `FileAccessPolicyTest` | `../`, `/etc/passwd`, symlinked file and dir escapes, no existence leaks, secrets/excluded dirs, NUL/blank paths, bad roots fail fast |
| `FileServiceTest` | Listing order/hiding/limits/modified time; read content, bad UTF-8, binary, size boundary, line ranges (defaults, big files, cut on a line boundary, overlong line, bad bounds, empty file); search literal-not-regex, glob/path scope, context lines, both caps, long lines, overlapping roots |
| `FolderInsightsTest` | Tree layout/depth/limits/hidden entries; find by name and relative-path glob, never secrets or symlinks; overview totals, file types, project files, largest/newest, scan cap, overlapping roots; refusals |
| `FileToolsTest` | Wire format: success JSON, `REASON: detail`, no internal details leaked; log stats keep counts, drop strings; timer per tool and outcome |
| `FilesPropertiesTest` | No roots means no start; env list binding; secure defaults; limit validation |

Tests build a small fake project with decoys (`TestWorkspace`): a `.env` secret, a `target/` dir, a binary, an
oversized file, and a symlink pointing outside. Every test checks that the sandbox hides or refuses them.

**Manual check:** run the curl steps against this repo. `search_code` for `RequestIdFilter` finds
`RequestIdFilter.java` and its test; `read_file` with `../../../.ssh/id_rsa` returns
`ACCESS_DENIED: path is outside the allowed roots`.

More: [M1 milestone report](../docs/milestones/M1.md) · [ADR-0002 (MCP stack)](../docs/decisions/ADR-0002-mcp-server-stack.md) · [developer guide](../docs/DEVELOPER-GUIDE.md)
