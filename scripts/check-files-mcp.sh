#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────
# DevMate M1 acceptance check: is the Files MCP server working?
#
# Builds a throw-away fake project (with traps: a secret, an escaping symlink, a binary, a huge
# file, a build dir), starts the real server jar sandboxed to it, then runs every test case from
# docs/testing/M1-files-mcp-test-cases.md over raw MCP (curl + JSON-RPC) and prints PASS/FAIL.
#
#   scripts/check-files-mcp.sh            # run all cases, stop the server afterwards
#   PORT=18300 scripts/check-files-mcp.sh # use another port
#   KEEP=1 scripts/check-files-mcp.sh     # leave the server running to explore by hand
#
# Exit code 0 = all cases passed. Needs: JDK 25, curl, jq.
# ─────────────────────────────────────────────────────────────────────────────────────────────
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT_DIR/scripts/support/common.sh"
PORT=${PORT:-18191}
URL="http://127.0.0.1:${PORT}/mcp"
JAVA_HOME=$(pick_jdk)
JAVA="$JAVA_HOME/bin/java"
JAR="$ROOT_DIR/devmate-files-mcp/target/devmate-files-mcp-0.1.0-SNAPSHOT.jar"
WORK=$(mktemp -d -t devmate-m1-XXXX)
LOG="$WORK/server.log"
SERVER_PID=""
NOROOTS_PID=""

cleanup() {
    if [[ -n "$SERVER_PID" && -z "${KEEP:-}" ]]; then kill "$SERVER_PID" 2>/dev/null; fi
    [[ -n "$NOROOTS_PID" ]] && kill "$NOROOTS_PID" 2>/dev/null
    [[ -z "${KEEP:-}" ]] && rm -rf "$WORK"
}
trap cleanup EXIT


# ── prerequisites ─────────────────────────────────────────────────────────────────────────────
require_tools curl jq
[[ -n "$JAVA_HOME" ]] || { echo "JDK 25+ not found: set JAVA_HOME to a JDK 25 (the system java is $(java -version 2>&1 | head -1))"; exit 2; }
if [[ ! -f "$JAR" ]]; then
    echo "Building devmate-files-mcp…"
    (cd "$ROOT_DIR" && JAVA_HOME="$JAVA_HOME" ./mvnw -B -q -pl devmate-files-mcp -am package -DskipTests) || exit 2
fi

# ── fixture: a fake project + things the sandbox must hide or refuse ──────────────────────────
FIX="$WORK/project"; OUTSIDE="$WORK/outside"
mkdir -p "$FIX/src/main/java/com/acme" "$FIX/src/main/resources" "$FIX/target" "$OUTSIDE"
echo '# Acme payments' > "$FIX/README.md"
echo 'DB_PASSWORD=KafkaTemplate-secret' > "$FIX/.env"
printf 'package com.acme;\n\nclass PaymentProducer {\n    private final KafkaTemplate<String, String> template;\n}\n' \
    > "$FIX/src/main/java/com/acme/PaymentProducer.java"
printf 'package com.acme;\n// uses kafkatemplate indirectly\n' > "$FIX/src/main/java/com/acme/PaymentService.java"
printf 'package com.acme;\n@Bean KafkaTemplate<?, ?> kafkaTemplate() {}\n' > "$FIX/src/main/java/com/acme/KafkaConfig.java"
echo 'spring.kafka.bootstrap-servers: localhost:9092' > "$FIX/src/main/resources/application.yml"
echo 'KafkaTemplate generated' > "$FIX/target/Generated.java"
printf '\x89PNG\x00\x00KafkaTemplate' > "$FIX/logo.png"
{ echo KafkaTemplate; head -c 1100000 /dev/zero | tr '\0' 'x'; } > "$FIX/big.txt"
echo 'KafkaTemplate outside the sandbox' > "$OUTSIDE/secret.txt"
ln -s "$OUTSIDE/secret.txt" "$FIX/escape.txt"

echo
echo "DevMate Files MCP — M1 acceptance check"
echo "  sandbox root : $FIX"
echo "  endpoint     : $URL"
echo "  JDK          : $JAVA_HOME"
echo

# ── MCP helpers ───────────────────────────────────────────────────────────────────────────────
SID=""
HEADERS=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
rpc() {   # rpc <json-body> → JSON-RPC response (plain JSON or the SSE data line)
    curl -s "${HEADERS[@]}" ${SID:+-H "Mcp-Session-Id: $SID"} "$URL" -d "$1" | sed -n 's/^data://p; /^{/p' | head -1
}
call() {  # call <tool> <arguments-json> → JSON-RPC response
    rpc "{\"jsonrpc\":\"2.0\",\"id\":$RANDOM,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}"
}
text()    { jq -r '.result.content[0].text // empty' <<<"$1"; }
isError() { jq -r '.result.isError // false' <<<"$1"; }

echo "Startup & configuration"
# ── TC-01: refuses to start without allowed roots when client roots are off ───────────────────
DEVMATE_FILES_MCP_PORT=$((PORT + 1)) DEVMATE_FILESYSTEM_ALLOWED_ROOTS="" DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=false \
    timeout 60 "$JAVA" -jar "$JAR" > "$WORK/noroots.log" 2>&1
code=$?
result TC-01 "refuses to start without DEVMATE_FILESYSTEM_ALLOWED_ROOTS when client roots are off" \
    "$([[ $code -ne 0 && $code -ne 124 ]] && grep -q 'set devmate.filesystem.allowed-roots' "$WORK/noroots.log" && echo true || echo false)" \
    "exit=$code"

# ── TC-36: default setup (no roots, client roots on) starts and sees nothing until approved ───
NR_PORT=$((PORT + 1))
DEVMATE_FILES_MCP_PORT=$NR_PORT DEVMATE_FILESYSTEM_ALLOWED_ROOTS="" "$JAVA" -jar "$JAR" > "$WORK/clientroots.log" 2>&1 &
NOROOTS_PID=$!
wait_for_health "http://127.0.0.1:$NR_PORT/actuator/health" "$NOROOTS_PID"
nr_init() {
    curl -s -D "$WORK/nr-headers" -o /dev/null "${HEADERS[@]}" "$URL" -d \
        '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"tc36","version":"1"}}}'
    grep -i '^mcp-session-id' "$WORK/nr-headers" | cut -d' ' -f2 | tr -d '\r'
}
NR_URL="http://127.0.0.1:$NR_PORT/mcp"
NR_SID=$(URL=$NR_URL nr_init)
URL=$NR_URL SID=$NR_SID rpc '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
nr_list=$(URL=$NR_URL SID=$NR_SID call list_files '{}')
nr_read=$(URL=$NR_URL SID=$NR_SID call read_file "{\"path\":\"$FIX/README.md\"}")
kill "$NOROOTS_PID" 2>/dev/null; NOROOTS_PID=""
result TC-36 "no roots + client roots on (default): starts, lists nothing, denies reads until approved" \
    "$(grep -q 'allow-client-roots=true' "$WORK/clientroots.log" \
        && [[ $(text "$nr_list" | jq -r '.entries | length' 2>/dev/null) == 0 ]] \
        && [[ $(text "$nr_read") == 'ACCESS_DENIED: path is outside the allowed roots' ]] \
        && echo true || echo false)" "list=$(text "$nr_list") read=$(text "$nr_read")"

# ── start the real server ─────────────────────────────────────────────────────────────────────
DEVMATE_FILES_MCP_PORT=$PORT DEVMATE_FILESYSTEM_ALLOWED_ROOTS="$FIX" \
DEVMATE_HTTP_ALLOWED_ORIGINS="http://localhost:6274" \
    "$JAVA" -jar "$JAR" > "$LOG" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 60); do
    curl -sf "http://127.0.0.1:${PORT}/actuator/health" >/dev/null && break
    kill -0 "$SERVER_PID" 2>/dev/null || break
    sleep 0.5
done
health=$(curl -s "http://127.0.0.1:${PORT}/actuator/health")
result TC-02 "starts and /actuator/health is UP" "$([[ $(jq -r .status <<<"$health" 2>/dev/null) == UP ]] && echo true || echo false)" "$health"
[[ $(jq -r .status <<<"$health" 2>/dev/null) == UP ]] || { echo; echo "Server did not start; log: $LOG"; tail -20 "$LOG"; exit 1; }

listen=$(ss -ltn 2>/dev/null | grep ":$PORT ")
result TC-03 "listens on loopback only (127.0.0.1)" "$([[ "$listen" =~ 127\.0\.0\.1\]?:$PORT && "$listen" != *"0.0.0.0:$PORT"* && "$listen" != *"[::]:$PORT"* ]] && echo true || echo false)" "$listen"

# ── protocol ──────────────────────────────────────────────────────────────────────────────────
echo; echo "MCP protocol"
init=$(curl -s -D "$WORK/headers" "${HEADERS[@]}" "$URL" -d \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check-script","version":"1"}}}' \
    | sed -n 's/^data://p; /^{/p' | head -1)
SID=$(grep -i '^mcp-session-id' "$WORK/headers" | cut -d' ' -f2 | tr -d '\r')
result TC-04 "initialize → serverInfo devmate-files, tools capability, session id" \
    "$([[ $(jq -r .result.serverInfo.name <<<"$init") == devmate-files && $(jq -r '.result.capabilities.tools != null' <<<"$init") == true && -n "$SID" ]] && echo true || echo false)" "$init"

code=$(curl -s -o /dev/null -w '%{http_code}' "${HEADERS[@]}" -H "Mcp-Session-Id: $SID" "$URL" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}')
result TC-05 "notifications/initialized → 202 Accepted" "$([[ $code == 202 ]] && echo true || echo false)" "$code"

tools=$(rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
names=$(jq -r '[.result.tools[].name] | sort | join(",")' <<<"$tools")
result TC-06 "tools/list → exactly list_files, read_file, search_code" "$([[ $names == list_files,read_file,search_code ]] && echo true || echo false)" "$names"
result TC-07 "every tool is readOnlyHint=true, destructiveHint=false" \
    "$(jq -r '[.result.tools[].annotations | .readOnlyHint == true and .destructiveHint == false] | all' <<<"$tools")" "$tools"
result TC-08 "search_code schema: query required; path, glob optional" \
    "$(jq -r '.result.tools[] | select(.name=="search_code") | .inputSchema | (.required == ["query"]) and (.properties | has("path") and has("glob"))' <<<"$tools")"

r=$(rpc '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"delete_everything","arguments":{}}}')
result TC-09 "unknown tool → JSON-RPC error (not executed)" "$(jq -r 'has("error")' <<<"$r")" "$r"

code=$(SID="" ; curl -s -o /dev/null -w '%{http_code}' "${HEADERS[@]}" "$URL" -d '{"jsonrpc":"2.0","id":4,"method":"tools/list"}')
result TC-10 "request without Mcp-Session-Id is rejected (4xx)" "$([[ $code == 4* ]] && echo true || echo false)" "$code"

# ── tools: happy paths ────────────────────────────────────────────────────────────────────────
echo; echo "Tools — expected behaviour"
r=$(call list_files '{}')
result TC-11 "list_files() with no path → the allowed root" "$([[ $(text "$r" | jq -r '.entries[0].path') == "$(cd "$FIX" && pwd -P)" ]] && echo true || echo false)" "$(text "$r")"

r=$(call list_files "{\"path\":\"$FIX\"}")
listed=$(text "$r" | jq -r '[.entries[].name] | join(",")')
result TC-12 "list_files(root) → src first; .env and target hidden" \
    "$([[ $listed == src,* && $listed != *.env* && $listed != *target* ]] && echo true || echo false)" "$listed"

r=$(call read_file '{"path":"src/main/java/com/acme/KafkaConfig.java"}')
result TC-13 "read_file(relative path) → file content" "$([[ $(text "$r" | jq -r .content) == *'@Bean KafkaTemplate'* ]] && echo true || echo false)" "$(text "$r")"

r=$(call search_code '{"query":"KafkaTemplate"}')
found=$(text "$r" | jq -r '[.matches[].path | split("/") | last] | unique | join(",")')
result TC-14 "search_code(\"KafkaTemplate\") → KafkaConfig, PaymentProducer, PaymentService only" \
    "$([[ $found == KafkaConfig.java,PaymentProducer.java,PaymentService.java ]] && echo true || echo false)" "$found"

line=$(text "$r" | jq -r '.matches[] | select(.path | endswith("PaymentProducer.java")) | "\(.line):\(.text)"')
result TC-15 "search_code result has 1-based line number and the line text" \
    "$([[ $line == '4:private final KafkaTemplate<String, String> template;' ]] && echo true || echo false)" "$line"

r=$(call search_code '{"query":"kafka","glob":"*.yml"}')
found=$(text "$r" | jq -r '[.matches[].path | split("/") | last] | join(",")')
result TC-16 "search_code(glob=*.yml) → only application.yml" "$([[ $found == application.yml ]] && echo true || echo false)" "$found"

r=$(call search_code '{"query":"a.*b"}')
result TC-17 "search_code treats the query as literal text, not regex" "$([[ $(text "$r" | jq -r '.matches | length') == 0 ]] && echo true || echo false)" "$(text "$r")"

# ── tools: security ───────────────────────────────────────────────────────────────────────────
echo; echo "Tools — security (each must be refused)"
expect_error() {  # expect_error <id> <description> <tool> <args> <REASON>
    local r; r=$(call "$3" "$4")
    local t; t=$(text "$r")
    result "$1" "$2 → $5" "$([[ $(isError "$r") == true && $t == "$5:"* && $t != *"$OUTSIDE"* && $t != *KafkaTemplate-secret* ]] && echo true || echo false)" "$t"
}
expect_error TC-18 "read_file(\"../outside/secret.txt\") traversal" read_file '{"path":"../outside/secret.txt"}' ACCESS_DENIED
expect_error TC-19 "read_file(\"/etc/passwd\") absolute path outside" read_file '{"path":"/etc/passwd"}' ACCESS_DENIED
expect_error TC-20 "read_file(\"escape.txt\") symlink pointing outside" read_file '{"path":"escape.txt"}' ACCESS_DENIED
expect_error TC-21 "read_file(\".env\") secret file" read_file '{"path":".env"}' ACCESS_DENIED
expect_error TC-22 "read_file(\"target/Generated.java\") excluded dir" read_file '{"path":"target/Generated.java"}' ACCESS_DENIED
expect_error TC-23 "read_file(\"/no/such/dir\") outside, non-existent (no probing)" read_file '{"path":"/no/such/dir"}' ACCESS_DENIED
expect_error TC-24 "read_file(\"logo.png\") binary" read_file '{"path":"logo.png"}' BINARY_FILE
expect_error TC-25 "read_file(\"big.txt\") over 1 MB" read_file '{"path":"big.txt"}' TOO_LARGE
expect_error TC-26 "read_file(\"nope.txt\") missing inside root" read_file '{"path":"nope.txt"}' NOT_FOUND
expect_error TC-27 "read_file(\"src\") a directory" read_file '{"path":"src"}' NOT_A_FILE
expect_error TC-28 "search_code(\" \") blank query" search_code '{"query":" "}' INVALID_ARGUMENT
expect_error TC-29 "search_code(path=\"../outside\")" search_code '{"query":"x","path":"../outside"}' ACCESS_DENIED

r=$(call search_code '{"query":"KafkaTemplate"}')
leaks=$(text "$r" | jq -r '[.matches[].path | select(test("\\.env|target/|big\\.txt|logo\\.png|escape|outside"))] | length')
result TC-30 "search_code never returns secrets, build output, binaries, big or outside files" "$([[ $leaks == 0 ]] && echo true || echo false)" "$(text "$r")"

# ── transport security ────────────────────────────────────────────────────────────────────────
echo; echo "Transport security"
code=$(curl -s -o /dev/null -w '%{http_code}' "${HEADERS[@]}" -H 'Origin: http://evil.example' "$URL" -d '{"jsonrpc":"2.0","id":9,"method":"tools/list"}')
result TC-31 "browser request from a foreign Origin → 403" "$([[ $code == 403 ]] && echo true || echo false)" "$code"
code=$(curl -s -o /dev/null -w '%{http_code}' "${HEADERS[@]}" -H 'Origin: http://localhost:6274' "$URL" -d \
    '{"jsonrpc":"2.0","id":10,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"o","version":"1"}}}')
result TC-32 "allow-listed Origin (MCP Inspector) → 200" "$([[ $code == 200 ]] && echo true || echo false)" "$code"

LAN_IP=$(ip -4 addr show scope global 2>/dev/null | grep -oE 'inet [0-9.]+' | head -1 | cut -d' ' -f2)
if [[ -n "$LAN_IP" ]]; then
    code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://$LAN_IP:$PORT/actuator/health")
    result TC-33 "not reachable from the network ($LAN_IP:$PORT)" "$([[ $code == 000 ]] && echo true || echo false)" "http $code"
fi

leaked=$(grep -c 'KafkaTemplate-secret\|private final KafkaTemplate' "$LOG")
result TC-34 "server log contains no file contents" "$([[ $leaked == 0 ]] && echo true || echo false)" "$leaked matching lines"
result TC-35 "server log records tool name and latency" "$(grep -q 'search_code\].*tool call succeeded durationMs=' "$LOG" && echo true || echo false)"

# ── summary ───────────────────────────────────────────────────────────────────────────────────
echo
TOTAL=$((PASSED + FAILED))
if [[ $FAILED -eq 0 ]]; then
    echo "$(green "ALL $TOTAL CASES PASSED") — the Files MCP server is working."
else
    echo "$(red "$FAILED of $TOTAL CASES FAILED") — server log: $LOG"
fi
if [[ -n "${KEEP:-}" ]]; then
    echo "KEEP=1: server still running (pid $SERVER_PID) at $URL, sandbox $FIX"
fi
[[ $FAILED -eq 0 ]]
