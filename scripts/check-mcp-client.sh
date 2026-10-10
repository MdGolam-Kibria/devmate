#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────
# M2 MCP client acceptance check: docs/testing/M2-mcp-client-test-cases.md, against the packaged jars.
#
# Starts the real devmate-files-mcp jar on a generated fixture, a deliberately misbehaving fake MCP
# server (scripts/support/fake_mcp.py) and the devmate-client jar configured with both. No LLM is
# involved: OLLAMA_BASE_URL points at a closed port. Every case goes through DevMate's /api/mcp API.
# Prints PASS/FAIL per case.
#
#   scripts/check-mcp-client.sh          # ~40 s
#   KEEP=1 scripts/check-mcp-client.sh   # leave DevMate, files MCP and fake MCP running afterwards
#
# Exit code 0 = all cases passed. Needs: JDK 25, curl, jq, python3.
# ─────────────────────────────────────────────────────────────────────────────────────────────
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT_DIR/scripts/support/common.sh"

PORT=${PORT:-18510}
FILES_PORT=$((PORT + 1))
FAKE_PORT=$((PORT + 2))
DOWN_PORT=$((PORT + 3))          # nothing listens here
PORT_B=$((PORT + 4))             # second DevMate instance (outage cases)
NO_LLM_PORT=$((PORT + 5))        # nothing listens here either
BASE="http://127.0.0.1:${PORT}"
BASE_B="http://127.0.0.1:${PORT_B}"
FILES="http://127.0.0.1:${FILES_PORT}"
FAKE="http://127.0.0.1:${FAKE_PORT}"
JAR="$ROOT_DIR/devmate-client/target/devmate-client-0.1.0-SNAPSHOT.jar"
FILES_JAR="$ROOT_DIR/devmate-files-mcp/target/devmate-files-mcp-0.1.0-SNAPSHOT.jar"
WORK=$(mktemp -d -t devmate-mcpc-XXXX)
APP_PID=""; APP_B_PID=""; FILES_PID=""; FAKE_PID=""

cleanup() {
    [[ -n "${KEEP:-}" ]] && return
    local pid
    for pid in "$APP_PID" "$APP_B_PID" "$FILES_PID" "$FAKE_PID"; do
        [[ -n "$pid" ]] && kill "$pid" 2>/dev/null
    done
    rm -rf "$WORK"
}
trap cleanup EXIT

require_tools curl jq python3
JAVA_HOME=$(pick_jdk)
[[ -n "$JAVA_HOME" ]] || { echo "JDK 25+ not found: set JAVA_HOME to a JDK 25"; exit 2; }
if [[ ! -f "$JAR" || ! -f "$FILES_JAR" ]]; then
    echo "Building devmate-client and devmate-files-mcp…"
    (cd "$ROOT_DIR" && JAVA_HOME="$JAVA_HOME" ./mvnw -B -q package -DskipTests) || exit 2
fi

has()  { [[ "$1" == *"$2"* ]] && echo true || echo false; }
not()  { [[ "$1" == true ]] && echo false || echo true; }
all()  { local v; for v in "$@"; do [[ "$v" == true ]] || { echo false; return; }; done; echo true; }
ms()   { echo $(( $(date +%s%N) / 1000000 )); }

# call <tool> <json body> [base] → sets STATUS and BODY
call() {
    local out
    out=$(curl -s -m 30 -w '\n%{http_code}' -X POST "${3:-$BASE}/api/mcp/tools/$1/call" \
        -H 'Content-Type: application/json' -d "$2")
    STATUS=$(tail -n1 <<<"$out"); BODY=$(sed '$d' <<<"$out")
}
code()  { jq -r '.errorCode // .code // .error // empty' <<<"$BODY"; }
fake_calls() { curl -s "$FAKE/__calls" | jq -r --arg t "$1" '.calls[$t] // 0'; }

# ── fixture ───────────────────────────────────────────────────────────────────────────────────
WS="$WORK/ws/shop"
mkdir -p "$WS/src/main/java/shop" "$WS/target"
cat > "$WS/src/main/java/shop/PaymentProducer.java" <<'EOF'
package shop;
class PaymentProducer { private final KafkaTemplate<String, String> kafka; }
EOF
cat > "$WS/src/main/java/shop/PaymentService.java" <<'EOF'
package shop;
class PaymentService { void pay() { /* publishes to Kafka via PaymentProducer */ } }
EOF
cat > "$WS/src/main/java/shop/KafkaConfig.java" <<'EOF'
package shop;
class KafkaConfig { /* Kafka producer factory */ }
EOF
echo "class Generated { Kafka gen; }" > "$WS/target/Generated.java"     # build dir: never searched
echo "DB_PASSWORD=hunter2-TOPSECRET" > "$WS/.env"                       # secret: denied
echo "notes: TOPSECRET-CONTENT lives here" > "$WS/notes.txt"

start_files() {
    DEVMATE_FILESYSTEM_ALLOWED_ROOTS="$WORK/ws" DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=false \
        DEVMATE_FILES_MCP_PORT=$FILES_PORT "$JAVA_HOME/bin/java" -jar "$FILES_JAR" >> "$WORK/files.log" 2>&1 &
    FILES_PID=$!
    wait_for_health "$FILES/actuator/health" "$FILES_PID"
}
# start_app <port> <log> <extra args…> → pid in LAST_PID
start_app() {
    local port=$1 log=$2; shift 2
    DEVMATE_PORT=$port DEVMATE_OPEN_BROWSER=false OLLAMA_BASE_URL="http://127.0.0.1:$NO_LLM_PORT" \
        DEVMATE_FILES_MCP_URL="$FILES/mcp" MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE="${EXPOSE:-health,info}" \
        "$JAVA_HOME/bin/java" -jar "$JAR" --devmate.llm.provider=ollama --devmate.llm.model=none "$@" \
        > "$log" 2>&1 &
    LAST_PID=$!
}

echo
echo "DevMate M2 — MCP client acceptance check"
echo "  DevMate  : $BASE/api/mcp (no LLM)"
echo "  servers  : files → $FILES/mcp (real jar) · fake → $FAKE/mcp (misbehaving) · down → :$DOWN_PORT (nothing)"
echo "  JDK      : $JAVA_HOME"
echo

python3 "$ROOT_DIR/scripts/support/fake_mcp.py" "$FAKE_PORT" > "$WORK/fake.log" 2>&1 &
FAKE_PID=$!
start_files
EXPOSE=health,info,metrics start_app "$PORT" "$WORK/app.log" \
    --devmate.mcp.servers.fake.url="$FAKE/mcp" --devmate.mcp.servers.fake.request-timeout=1s \
    --devmate.mcp.servers.fake.max-response-size=1MB
APP_PID=$LAST_PID

# ── startup & configuration ───────────────────────────────────────────────────────────────────
echo "Startup & configuration"
wait_for_health "$BASE/actuator/health" "$APP_PID"
result TC-01 "DevMate starts with two MCP servers configured; health UP" \
    "$(has "$(curl -s "$BASE/actuator/health")" '"status":"UP"')"
for _ in $(seq 1 20); do grep -q 'MCP startup discovery' "$WORK/app.log" && break; sleep 0.5; done
result TC-02 "startup discovery connects, initializes and lists tools (logged)" "$(all \
    "$(grep -qE 'MCP connected server=files .*serverInfo=devmate-files .*protocol=20' "$WORK/app.log" && echo true || echo false)" \
    "$(grep -qE 'MCP tools discovered server=files tools=[0-9]+' "$WORK/app.log" && echo true || echo false)" \
    "$(grep -qE 'MCP startup discovery: servers=2 tools=[0-9]+ unavailable=\[\]' "$WORK/app.log" && echo true || echo false)")" \
    "$(grep 'MCP startup' "$WORK/app.log")"

start_app "$PORT_B" "$WORK/app-bad-url.log" --devmate.mcp.servers.files.url=file:///etc/passwd
bad=$LAST_PID; for _ in $(seq 1 40); do kill -0 "$bad" 2>/dev/null || break; sleep 0.5; done
alive=$(kill -0 "$bad" 2>/dev/null && echo true || echo false); [[ $alive == true ]] && kill "$bad"
result TC-03 "non-http(s) server URL → DevMate refuses to start" "$(all "$(not "$alive")" \
    "$(grep -q 'MCP server url must be an absolute http(s) URL' "$WORK/app-bad-url.log" && echo true || echo false)")"

start_app "$PORT_B" "$WORK/app-bad-cred.log" --devmate.mcp.servers.files.url="http://admin:s3cr3tpw@127.0.0.1:1/mcp"
bad=$LAST_PID; for _ in $(seq 1 40); do kill -0 "$bad" 2>/dev/null || break; sleep 0.5; done
alive=$(kill -0 "$bad" 2>/dev/null && echo true || echo false); [[ $alive == true ]] && kill "$bad"
result TC-04 "credentials in a server URL → refused at startup, password not logged" "$(all "$(not "$alive")" \
    "$(grep -q 'must not carry credentials' "$WORK/app-bad-cred.log" && echo true || echo false)" \
    "$(grep -q 's3cr3tpw' "$WORK/app-bad-cred.log" && echo false || echo true)")"

start_app "$PORT_B" "$WORK/app-bad-name.log" --devmate.mcp.servers.Bad_Name.url="$FAKE/mcp"
bad=$LAST_PID; for _ in $(seq 1 40); do kill -0 "$bad" 2>/dev/null || break; sleep 0.5; done
alive=$(kill -0 "$bad" 2>/dev/null && echo true || echo false); [[ $alive == true ]] && kill "$bad"
result TC-05 "invalid server name → refused at startup" "$(all "$(not "$alive")" \
    "$(grep -q 'is not a valid server name' "$WORK/app-bad-name.log" && echo true || echo false)")"

# ── protocol / API ────────────────────────────────────────────────────────────────────────────
echo; echo "Protocol / API: connect → initialize → discover → register → execute"
servers=$(curl -s "$BASE/api/mcp/servers")
result TC-06 "GET /api/mcp/servers: files CONNECTED, serverInfo + protocol from initialize, tools counted" "$(all \
    "$(jq -e '.servers[0] | .name=="files" and .state=="CONNECTED" and .serverName=="devmate-files"
              and (.protocolVersion|test("^20")) and .tools>=6 and .error==null' <<<"$servers" >/dev/null && echo true || echo false)" \
    "$(jq -e '.servers[1] | .name=="fake" and .state=="CONNECTED" and .serverName=="fake-mcp"' <<<"$servers" >/dev/null && echo true || echo false)")" \
    "$servers"
tools=$(curl -s "$BASE/api/mcp/tools")
result TC-07 "GET /api/mcp/tools: files tools with server, readOnly hint and input schema" \
    "$(jq -e '[.tools[] | select(.server=="files")] | (map(.name) | contains(["list_files","read_file","search_code"]))
              and all(.readOnly) and all(.inputSchema.type=="object")' <<<"$tools" >/dev/null && echo true || echo false)" \
    "$(jq -c '[.tools[] | .server+":"+.name]' <<<"$tools")"
result TC-08 "tools are discovered, not hardcoded: the fake server's tools appear too" \
    "$(jq -e '[.tools[] | select(.server=="fake") | .name] | contains(["fake_echo","fake_slow","fake_delete"])' <<<"$tools" >/dev/null && echo true || echo false)"
result TC-09 "name clash: search_code is registered once, for the higher-priority server (files)" \
    "$(jq -e '[.tools[] | select(.name=="search_code")] | length==1 and .[0].server=="files"' <<<"$tools" >/dev/null && echo true || echo false)"

call search_code '{"arguments":{"query":"Kafka"}}'
result TC-10 "DoD: search_code(\"Kafka\") without an LLM → PaymentProducer, PaymentService, KafkaConfig" "$(all \
    "$([[ $STATUS == 200 ]] && echo true || echo false)" \
    "$(jq -e '.server=="files" and .error==false and .errorCode==null' <<<"$BODY" >/dev/null && echo true || echo false)" \
    "$(has "$BODY" 'PaymentProducer.java')" "$(has "$BODY" 'PaymentService.java')" "$(has "$BODY" 'KafkaConfig.java')" \
    "$(not "$(has "$BODY" 'Generated.java')")" "$([[ $(fake_calls search_code) == 0 ]] && echo true || echo false)")" \
    "$STATUS ${BODY:0:300}"
call read_file "{\"arguments\":{\"path\":\"$WS/src/main/java/shop/KafkaConfig.java\"}}"
result TC-11 "read_file through the client returns the file" \
    "$(all "$([[ $STATUS == 200 ]] && echo true || echo false)" "$(has "$BODY" 'Kafka producer factory')")" "$STATUS"
before=$(grep -c 'MCP tools discovered server=files' "$WORK/app.log")
curl -s "$BASE/api/mcp/tools" >/dev/null
same=$(grep -c 'MCP tools discovered server=files' "$WORK/app.log")
curl -s "$BASE/api/mcp/tools?refresh=true" >/dev/null
after=$(grep -c 'MCP tools discovered server=files' "$WORK/app.log")
result TC-12 "registry is cached; ?refresh=true re-runs tools/list" \
    "$([[ $same == "$before" && $after -gt $same ]] && echo true || echo false)" "$before/$same/$after"
call fake_echo '{"arguments":{"message":"hi"}}'
result TC-13 "tool on the second server is routed there" \
    "$(all "$([[ $STATUS == 200 ]] && echo true || echo false)" "$(jq -e '.server=="fake" and .text=="echo: hi"' <<<"$BODY" >/dev/null && echo true || echo false)")" "$BODY"

# ── error handling ────────────────────────────────────────────────────────────────────────────
echo; echo "Error handling"
call read_file "{\"arguments\":{\"path\":\"$WS/nope.txt\"}}"
result TC-14 "tool reports a failure → 200, TOOL_ERROR, server's reason kept" \
    "$(all "$([[ $STATUS == 200 ]] && echo true || echo false)" "$([[ $(code) == TOOL_ERROR ]] && echo true || echo false)" \
        "$(jq -e '.error==true and (.text|startswith("NOT_FOUND:"))' <<<"$BODY" >/dev/null && echo true || echo false)")" "$STATUS $BODY"
call no_such_tool '{}'
result TC-15 "unknown tool → 404 UNKNOWN_TOOL" \
    "$(all "$([[ $STATUS == 404 ]] && echo true || echo false)" "$([[ $(code) == UNKNOWN_TOOL ]] && echo true || echo false)")" "$STATUS $BODY"
call search_code '{"arguments":{}}'
s1=$STATUS; c1=$(code)
call fake_echo '{"arguments":{"message":42}}'
s2=$STATUS; c2=$(code)
result TC-16 "arguments breaking the input schema → 400 INVALID_ARGUMENTS, never sent to the server" "$(all \
    "$([[ $s1 == 400 && $c1 == INVALID_ARGUMENTS && $s2 == 400 && $c2 == INVALID_ARGUMENTS ]] && echo true || echo false)" \
    "$([[ $(fake_calls fake_echo) == 1 ]] && echo true || echo false)")" "$s1 $c1 / $s2 $c2 / echo calls $(fake_calls fake_echo)"
call fake_strict '{}'
result TC-17 "server rejects the params (JSON-RPC -32602) → 400 INVALID_ARGUMENTS" \
    "$(all "$([[ $STATUS == 400 && $(code) == INVALID_ARGUMENTS ]] && echo true || echo false)" "$(has "$BODY" 'depth must be <= 8')")" "$STATUS $BODY"
call fake_crash '{}'
result TC-18 "server error (JSON-RPC -32603) → 502 SERVER_ERROR" \
    "$(all "$([[ $STATUS == 502 && $(code) == SERVER_ERROR ]] && echo true || echo false)" "$(has "$BODY" '(JSON-RPC -32603)')")" "$STATUS $BODY"
inits=$(curl -s "$FAKE/__calls" | jq .initializes)
t0=$(ms); call fake_slow '{}'; took=$(( $(ms) - t0 ))
result TC-19 "slow tool → 504 TIMEOUT after the 1 s request timeout, not retried" \
    "$(all "$([[ $STATUS == 504 && $(code) == TIMEOUT ]] && echo true || echo false)" \
        "$([[ $took -lt 2500 && $(fake_calls fake_slow) == 1 ]] && echo true || echo false)")" "$STATUS ${took}ms calls=$(fake_calls fake_slow)"
call fake_echo '{"arguments":{"message":"after timeout"}}'
result TC-20 "after a timeout the same session keeps working (no new initialize)" \
    "$(all "$([[ $STATUS == 200 ]] && echo true || echo false)" "$([[ $(curl -s "$FAKE/__calls" | jq .initializes) == "$inits" ]] && echo true || echo false)")"
call fake_http500 '{}'
result TC-21 "HTTP 500 from the server → 503 MCP_UNAVAILABLE, TRANSPORT_ERROR" \
    "$([[ $STATUS == 503 && $(jq -r .code <<<"$BODY") == TRANSPORT_ERROR && $(jq -r .server <<<"$BODY") == fake ]] && echo true || echo false)" "$STATUS $BODY"
call fake_big '{}'
result TC-22 "5 MB response over the server's 1 MB cap → TRANSPORT_ERROR, not retried, DevMate healthy" "$(all \
    "$([[ $STATUS == 503 && $(jq -r .code <<<"$BODY") == TRANSPORT_ERROR && $(fake_calls fake_big) == 1 ]] && echo true || echo false)" \
    "$(has "$BODY" 'larger than max-response-size')" \
    "$(has "$(curl -s "$BASE/actuator/health")" '"status":"UP"')")" "$STATUS $BODY"
inits=$(curl -s "$FAKE/__calls" | jq .initializes)
curl -s "$FAKE/__expire" >/dev/null
call fake_echo '{"arguments":{"message":"new session"}}'
result TC-23 "session expired on the server (404) → new session, call succeeds" "$(all \
    "$([[ $STATUS == 200 ]] && has "$BODY" 'echo: new session' || echo false)" \
    "$([[ $(curl -s "$FAKE/__calls" | jq .initializes) -gt $inits ]] && echo true || echo false)")" "$STATUS $BODY"

kill "$FILES_PID"; wait "$FILES_PID" 2>/dev/null; FILES_PID=""
t0=$(ms); call read_file "{\"arguments\":{\"path\":\"$WS/notes.txt\"}}"; took=$(( $(ms) - t0 ))
result TC-24 "files MCP stopped → 503 CONNECTION_FAILED with a clear message, quickly (${took} ms)" "$(all \
    "$([[ $STATUS == 503 && $(jq -r .code <<<"$BODY") == CONNECTION_FAILED ]] && echo true || echo false)" \
    "$(has "$BODY" "Files MCP server is not reachable at $FILES/mcp")" "$([[ $took -lt 5000 ]] && echo true || echo false)")" "$STATUS $BODY"
start_files
call search_code '{"arguments":{"query":"Kafka"}}'
result TC-25 "files MCP restarted → DevMate reconnects on the next call, no restart needed" \
    "$(all "$([[ $STATUS == 200 ]] && echo true || echo false)" "$(has "$BODY" 'PaymentProducer.java')")" "$STATUS"

start_app "$PORT_B" "$WORK/app-b.log" --devmate.mcp.servers.down.url="http://127.0.0.1:$DOWN_PORT/mcp"
APP_B_PID=$LAST_PID
wait_for_health "$BASE_B/actuator/health" "$APP_B_PID"
servers=$(curl -s "$BASE_B/api/mcp/servers")
result TC-26 "a configured server is down → DevMate still starts; status UNAVAILABLE, CONNECTION_FAILED" \
    "$(jq -e '.servers[1] | .name=="down" and .state=="UNAVAILABLE" and .error=="CONNECTION_FAILED"
              and (.message|contains("connection refused"))' <<<"$servers" >/dev/null && echo true || echo false)" "$servers"
call search_code '{"arguments":{"query":"Kafka"}}' "$BASE_B"
result TC-27 "a down server doesn't hide the others: search_code still works" \
    "$([[ $STATUS == 200 ]] && has "$BODY" 'KafkaConfig.java' || echo false)" "$STATUS"
call git_log '{}' "$BASE_B"
result TC-28 "unknown tool while a server is down → 503 (it may be that server's), not 404" \
    "$([[ $STATUS == 503 && $(jq -r .server <<<"$BODY") == down ]] && echo true || echo false)" "$STATUS $BODY"
code_b=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_B/actuator/metrics")
kill "$APP_B_PID"; wait "$APP_B_PID" 2>/dev/null; APP_B_PID=""

# ── security / misuse ─────────────────────────────────────────────────────────────────────────
echo; echo "Security / misuse"
call fake_delete '{}'
result TC-29 "tool not declared read-only → 403 TOOL_NOT_ALLOWED, never sent to the server" \
    "$([[ $STATUS == 403 && $(code) == TOOL_NOT_ALLOWED && $(fake_calls fake_delete) == 0 ]] && echo true || echo false)" "$STATUS $BODY"
call read_file '{"arguments":{"path":"/etc/passwd"}}'
result TC-30 "the server's sandbox still applies: /etc/passwd → ACCESS_DENIED" \
    "$(jq -e '.errorCode=="TOOL_ERROR" and (.text|startswith("ACCESS_DENIED"))' <<<"$BODY" >/dev/null && ! grep -q 'root:' <<<"$BODY" && echo true || echo false)" "$BODY"
call read_file '{"arguments":{"path":"../../../../../../etc/passwd"}}'
result TC-31 "traversal through the client → ACCESS_DENIED" \
    "$(jq -e '.errorCode=="TOOL_ERROR" and (.text|startswith("ACCESS_DENIED"))' <<<"$BODY" >/dev/null && echo true || echo false)" "$BODY"
call read_file "{\"arguments\":{\"path\":\"$WS/.env\"}}"
result TC-32 "secret file → ACCESS_DENIED, content never returned" \
    "$(all "$(has "$BODY" 'ACCESS_DENIED')" "$(not "$(has "$BODY" 'hunter2')")")" "$BODY"
call 'bad%20name' '{}'; s1=$STATUS
call "$(printf 'a%.0s' {1..129})" '{}'; s2=$STATUS
result TC-33 "invalid tool names (space, 129 chars) → 400 INVALID_REQUEST" \
    "$([[ $s1 == 400 && $s2 == 400 ]] && has "$BODY" 'INVALID_REQUEST' || echo false)" "$s1 $s2"
f=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/mcp/tools/read_file/call" --data-urlencode "path=$WS/notes.txt")
t=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/mcp/tools/read_file/call" -H 'Content-Type: text/plain' -d '{}')
g=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/mcp/tools/read_file/call")
result TC-34 "cross-site form / text/plain POST → 415; GET → 405 (no CSRF, no drive-by calls)" \
    "$([[ $f == 415 && $t == 415 && $g == 405 ]] && echo true || echo false)" "$f $t $g"
h=$(curl -s -w '\n%{http_code}' -X POST "$BASE/api/mcp/tools/read_file/call" -H 'Host: rebind.evil.example' \
    -H 'Content-Type: application/json' -d "{\"arguments\":{\"path\":\"$WS/notes.txt\"}}")
l=$(curl -s -o /dev/null -w '%{http_code}' -H "Host: localhost:$PORT" "$BASE/api/mcp/servers")
result TC-42 "DNS rebinding: a foreign Host header → 403 HOST_NOT_ALLOWED, no file content; localhost still served" \
    "$(all "$([[ $(tail -n1 <<<"$h") == 403 && $l == 200 ]] && echo true || echo false)" "$(has "$h" HOST_NOT_ALLOWED)" \
        "$(not "$(has "$h" TOPSECRET)")")" "$h / localhost $l"
call read_file '{"arguments":'
result TC-35 "malformed JSON body → 400" "$([[ $STATUS == 400 ]] && echo true || echo false)" "$STATUS"
call fake_crash '{}'
result TC-36 "server-supplied error text is one bounded line (no newline, no escape codes)" \
    "$(jq -e '.text | (contains("\n") or contains("\u001b")) | not' <<<"$BODY" >/dev/null && echo true || echo false)" "$BODY"

# ── transport security & logging ──────────────────────────────────────────────────────────────
echo; echo "Transport security & logging"
LAN_IP=$(ip -4 addr show scope global 2>/dev/null | grep -oE 'inet [0-9.]+' | head -1 | cut -d' ' -f2)
if [[ -n "$LAN_IP" ]]; then
    c=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://$LAN_IP:$PORT/api/mcp/servers")
    result TC-37 "MCP API not reachable from the network ($LAN_IP)" "$([[ $c == 000 ]] && echo true || echo false)" "http $c"
else
    result TC-37 "MCP API not reachable from the network (no LAN address: loopback bind checked in config)" \
        "$(grep -q '127.0.0.1' "$ROOT_DIR/devmate-client/src/main/resources/application.yml" && echo true || echo false)"
fi
call search_code '{"arguments":{"query":"hunter2-query-secret"}}'
call read_file "{\"arguments\":{\"path\":\"$WS/notes.txt\"}}"
leaks=$(grep -cE 'hunter2-query-secret|TOPSECRET-CONTENT|KafkaTemplate<' "$WORK/app.log")
result TC-38 "arguments and results are never logged" "$([[ $leaks == 0 ]] && echo true || echo false)" "$leaks leaking lines"
result TC-39 "each call is logged with server, tool, outcome and duration" "$(all \
    "$(grep -qE 'MCP tools/call server=files tool=search_code outcome=SUCCESS durationMs=[0-9]+' "$WORK/app.log" && echo true || echo false)" \
    "$(grep -qE 'MCP tools/call server=fake tool=fake_slow outcome=TIMEOUT' "$WORK/app.log" && echo true || echo false)" \
    "$(grep -qE 'MCP tools/call server=fake tool=fake_delete outcome=TOOL_NOT_ALLOWED' "$WORK/app.log" && echo true || echo false)")"
metric=$(curl -s "$BASE/actuator/metrics/devmate.mcp.client.calls")
result TC-40 "timer devmate.mcp.client.calls tagged server/tool/outcome (when metrics are exposed)" \
    "$(jq -e '[.availableTags[].tag] | contains(["server","tool","outcome"])' <<<"$metric" >/dev/null && echo true || echo false)" "${metric:0:200}"
result TC-41 "metrics stay hidden by default (deny-by-default actuator)" "$([[ $code_b == 404 ]] && echo true || echo false)" "http $code_b"

summary "DevMate's MCP client connects, discovers, executes and handles every failure"
if [[ -n "${KEEP:-}" ]]; then
    echo "KEEP=1: DevMate $BASE (pid $APP_PID), files MCP $FILES (pid $FILES_PID), fake MCP $FAKE (pid $FAKE_PID)"
    echo "        try: curl -s -XPOST $BASE/api/mcp/tools/search_code/call -H 'Content-Type: application/json' -d '{\"arguments\":{\"query\":\"Kafka\"}}'"
fi
[[ $FAILED -eq 0 ]]
