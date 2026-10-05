#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────
# DevMate web UI / chat acceptance check: do the messages you send from the UI work?
#
# Starts the real devmate-client jar against a deterministic fake Ollama (scripts/support/
# fake_ollama.py), then sends every example message from docs/testing/UI-chat-test-cases.md
# through the web UI exactly like a browser (form POST, redirect, session cookie) and checks
# the rendered page. Prints PASS/FAIL per case. The folder Q&A cases also start the real
# devmate-files-mcp jar on a generated fixture folder.
#
#   scripts/check-client.sh             # deterministic run (fake model), ~30 s
#   LIVE=1 scripts/check-client.sh      # also send the LIVE cases to your real Ollama (llama3.2:3b)
#   KEEP=1 scripts/check-client.sh      # leave the app running on the fake model to click around
#
#   (KEEP=1 leaves the files MCP server running too: open the fixture folder printed at the end)
#
# Exit code 0 = all cases passed. Needs: JDK 25, curl, jq, python3.
# ─────────────────────────────────────────────────────────────────────────────────────────────
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT_DIR/scripts/support/common.sh"

PORT=${PORT:-18410}
FAKE_PORT=$((PORT + 1))
MCP_PORT=$((PORT + 2))
BASE="http://127.0.0.1:${PORT}"
FAKE="http://127.0.0.1:${FAKE_PORT}"
MCP="http://127.0.0.1:${MCP_PORT}"
JAR="$ROOT_DIR/devmate-client/target/devmate-client-0.1.0-SNAPSHOT.jar"
MCP_JAR="$ROOT_DIR/devmate-files-mcp/target/devmate-files-mcp-0.1.0-SNAPSHOT.jar"
WORK=$(mktemp -d -t devmate-ui-XXXX)
APP_PID=""; FAKE_PID=""; MCP_PID=""

cleanup() {
    [[ -n "${KEEP:-}" ]] && return
    [[ -n "$APP_PID" ]] && kill "$APP_PID" 2>/dev/null
    [[ -n "$FAKE_PID" ]] && kill "$FAKE_PID" 2>/dev/null
    [[ -n "$MCP_PID" ]] && kill "$MCP_PID" 2>/dev/null
    rm -rf "$WORK"
}
trap cleanup EXIT

require_tools curl jq python3
JAVA_HOME=$(pick_jdk)
[[ -n "$JAVA_HOME" ]] || { echo "JDK 25+ not found: set JAVA_HOME to a JDK 25"; exit 2; }
if [[ ! -f "$JAR" || ! -f "$MCP_JAR" ]]; then
    echo "Building devmate-client and devmate-files-mcp…"
    (cd "$ROOT_DIR" && JAVA_HOME="$JAVA_HOME" ./mvnw -B -q package -DskipTests) || exit 2
fi

# ── browser simulation ────────────────────────────────────────────────────────────────────────
JAR_COOKIES="$WORK/cookies-a"          # "browser A"
send() {      # send <message> → rendered page after POST /chat + redirect (like pressing Enter)
    curl -s -L -c "$JAR_COOKIES" -b "$JAR_COOKIES" "$BASE/chat" --data-urlencode "message=$1"
}
page() { curl -s -c "$JAR_COOKIES" -b "$JAR_COOKIES" "$BASE/"; }
calls() { curl -s "$FAKE/__count" | jq -r .calls; }
has()   { [[ "$1" == *"$2"* ]] && echo true || echo false; }
both()  { [[ "$1" == true && "$2" == true ]] && echo true || echo false; }

# ── start fake model + app ────────────────────────────────────────────────────────────────────
python3 "$ROOT_DIR/scripts/support/fake_ollama.py" "$FAKE_PORT" > "$WORK/fake.log" 2>&1 &
FAKE_PID=$!
DEVMATE_PORT=$PORT DEVMATE_OPEN_BROWSER=false OLLAMA_BASE_URL="$FAKE" DEVMATE_FILES_MCP_URL="$MCP/mcp" \
DEVMATE_BROWSE_START="$WORK/ws" \
    "$JAVA_HOME/bin/java" -jar "$JAR" --devmate.llm.provider=ollama --devmate.llm.model=fake-model \
    > "$WORK/app.log" 2>&1 &
APP_PID=$!

echo
echo "DevMate web UI — chat acceptance check"
echo "  UI    : $BASE/"
echo "  model : fake Ollama at $FAKE (deterministic replies)"
echo "  JDK   : $JAVA_HOME"
echo

echo "Startup"
wait_for_health "$BASE/actuator/health" "$APP_PID"
result UI-01 "app starts; /actuator/health is UP" "$(has "$(curl -s "$BASE/actuator/health")" '"status":"UP"')"
[[ $FAILED -eq 0 ]] || { echo "App did not start; log:"; tail -20 "$WORK/app.log"; exit 1; }

home=$(page)
result UI-02 "GET / shows the chat page with provider/model badge" \
    "$(both "$(has "$home" '<title>DevMate</title>')" "$(has "$home" '>fake-model<')")"
result UI-03 "empty state shows 'Ask DevMate' and 3 suggestions" \
    "$(both "$(has "$home" 'Ask DevMate')" "$([[ $(grep -c 'class="suggestion"' <<<"$home") == 3 ]] && echo true || echo false)")"
result UI-04 "footer says the model runs locally (Ollama)" "$(has "$home" 'nothing leaves this machine')"
result UI-05 "stylesheet is served" "$([[ $(curl -s -o /dev/null -w '%{http_code}' "$BASE/css/devmate.css") == 200 ]] && echo true || echo false)"
result UI-06 "startup log prints the UI address" "$(grep -q "DevMate UI ready at http://localhost:$PORT/" "$WORK/app.log" && echo true || echo false)"

echo; echo "Example messages (type these in the UI)"

p=$(send "Hello")
result UI-07 "'Hello' → your bubble + reply 'Echo: Hello'" "$(both "$(has "$p" '>Hello<')" "$(has "$p" 'Echo: Hello')")"
result UI-08 "reply shows model · tokens · time" "$(grep -qE 'fake-model · 12 in / 7 out tokens · [0-9]+ ms' <<<"$p" && echo true || echo false)"

msg='এই project-এ Kafka কোথায় ব্যবহার করা হয়েছে?'
p=$(send "$msg")
result UI-09 "Bangla question round-trips intact" "$(has "$p" "Echo: $msg")"

p=$(send 'Price < 100 & qty > 5 😀 "quotes"')
result UI-10 "symbols are escaped, emoji kept: < & > \" 😀" \
    "$(both "$(has "$p" 'Price &lt; 100 &amp; qty &gt; 5 😀 &quot;quotes&quot;')" "$(has "$p" 'Echo: Price &lt; 100')")"

p=$(send $'line one\nline two\nline three')
result UI-11 "multi-line message keeps its line breaks" "$(has "$p" $'line one\nline two\nline three')"

p=$(send "MARKDOWN: list 3 causes of Kafka consumer lag")
md=$(both "$(has "$p" '<strong>3 causes</strong>')" "$(has "$p" '<ol>')")
md=$(both "$md" "$(has "$p" '<code>max.poll.records</code>')")
md=$(both "$md" "$(has "$p" '<pre><code class="language-java">')")
result UI-12 "Markdown reply renders: bold, numbered list, inline code, code block" "$md"
result UI-13 "Markdown table renders" "$(both "$(has "$p" '<table>')" "$(has "$p" '<td>Slow handler</td>')")"

p=$(send '<script>alert("you")</script> <b>bold?</b>')
result UI-14 "HTML/script in YOUR message is shown as text, not run" \
    "$(both "$(has "$p" '&lt;script&gt;alert(&quot;you&quot;)&lt;/script&gt;')" "$([[ $p != *'<script>alert("you")'* ]] && echo true || echo false)")"

p=$(send "XSS please")
# Dangerous = executable markup or a javascript: URL inside an attribute. The same words as escaped text are fine.
xss=$([[ $p != *"<script>alert('xss')"* && $p != *'<img src=x'* ]] && ! grep -qiE '(href|src)="?javascript:' <<<"$p" && echo true || echo false)
result UI-15 "script, javascript: link and <img onerror> in the MODEL reply are neutralised" "$xss"
result UI-16 "…and still shown as readable text" "$(has "$p" '&lt;script&gt;alert(')"

p=$(page)
first=$(grep -n 'Echo: Hello' <<<"$p" | head -1 | cut -d: -f1)
last=$(grep -n "&lt;script&gt;alert('xss')" <<<"$p" | head -1 | cut -d: -f1)
result UI-17 "conversation keeps every message, oldest first" "$([[ -n $first && -n $last && $first -lt $last ]] && echo true || echo false)"

echo; echo "Input validation (must NOT reach the model)"
before=$(calls)
p=$(send "   ")
result UI-18 "blank message → 'Message must be 1–8000 characters.'" "$(has "$p" 'Message must be 1–8000 characters.')"
p=$(send "$(printf 'x%.0s' $(seq 1 8001))")
result UI-19 "8001-character message → rejected" "$(has "$p" 'Message must be 1–8000 characters.')"
result UI-20 "rejected messages were not sent to the model" "$([[ $(calls) == "$before" ]] && echo true || echo false)" "calls $before → $(calls)"
p=$(send "$(printf 'y%.0s' $(seq 1 8000))")
result UI-21 "exactly 8000 characters → accepted" "$(has "$p" 'Echo: yyyy')"

echo; echo "Errors"
start=$(date +%s)
p=$(send "FAIL: simulate a provider error")
took=$(( $(date +%s) - start ))
result UI-22 "provider error → red error message in the chat" \
    "$(both "$(has "$p" 'class="message error"')" "$(has "$p" 'LLM provider &#39;ollama&#39; failed: HTTP 500')")"
result UI-23 "provider error answers quickly (retries capped), took ${took}s" "$([[ $took -le 10 ]] && echo true || echo false)"
kill "$FAKE_PID" 2>/dev/null; wait "$FAKE_PID" 2>/dev/null; FAKE_PID=""
start=$(date +%s)
p=$(send "Hello while the model is down")
took=$(( $(date +%s) - start ))
result UI-24 "model server down → error message, page still works (${took}s)" \
    "$(both "$(has "$p" 'class="message error"')" "$(has "$p" 'Hello while the model is down')")"
python3 "$ROOT_DIR/scripts/support/fake_ollama.py" "$FAKE_PORT" > "$WORK/fake.log" 2>&1 &
FAKE_PID=$!; sleep 1
p=$(send "Hello again")
result UI-25 "model back → chat recovers without restarting DevMate" "$(has "$p" 'Echo: Hello again')"

echo; echo "Session & navigation"
before=$(calls); page >/dev/null; page >/dev/null
result UI-26 "refreshing the page does not re-send the last message" "$([[ $(calls) == "$before" ]] && echo true || echo false)"
JAR_COOKIES="$WORK/cookies-b"
p=$(page)
result UI-27 "a second browser has its own empty conversation" "$(both "$(has "$p" 'Ask DevMate')" "$([[ $p != *'Echo: Hello'* ]] && echo true || echo false)")"
JAR_COOKIES="$WORK/cookies-a"
p=$(curl -s -L -c "$JAR_COOKIES" -b "$JAR_COOKIES" -X POST "$BASE/chat/clear" -o /dev/null -w '%{redirect_url}'; page)
result UI-28 "'New chat' clears the conversation" "$(both "$(has "$p" 'Ask DevMate')" "$([[ $p != *'Echo: Hello'* ]] && echo true || echo false)")"
hdr=$(curl -s -D - -o /dev/null "$BASE/chat" --data-urlencode "message=cookie check")
result UI-29 "session cookie is HttpOnly + SameSite=Strict" "$(grep -qi 'set-cookie:.*HttpOnly.*SameSite=Strict' <<<"$hdr" && echo true || echo false)" "$(grep -i set-cookie <<<"$hdr")"
result UI-30 "redirect never puts the session id in the URL" "$(grep -qi '^location:.*jsessionid' <<<"$hdr" && echo false || echo true)" "$(grep -i location <<<"$hdr")"

echo; echo "JSON API (same model, for scripts)"
r=$(curl -s -X POST "$BASE/api/chat" -H 'Content-Type: application/json' -d '{"message":"Hello API"}')
result UI-31 "POST /api/chat → reply, provider, model, tokens" \
    "$([[ $(jq -r '.reply + "|" + .provider + "|" + .model + "|" + (.inputTokens|tostring)' <<<"$r") == 'Echo: Hello API|ollama|fake-model|12' ]] && echo true || echo false)" "$r"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/chat" -H 'Content-Type: application/json' -d '{"message":" "}')
result UI-32 "POST /api/chat blank message → 400" "$([[ $code == 400 ]] && echo true || echo false)" "$code"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/chat" -H 'Content-Type: application/json' -d '{"message":"FAIL"}')
result UI-33 "POST /api/chat provider error → 502" "$([[ $code == 502 ]] && echo true || echo false)" "$code"

echo; echo "Exposure"
LAN_IP=$(ip -4 addr show scope global 2>/dev/null | grep -oE 'inet [0-9.]+' | head -1 | cut -d' ' -f2)
if [[ -n "$LAN_IP" ]]; then
    code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://$LAN_IP:$PORT/")
    result UI-34 "UI not reachable from the network ($LAN_IP:$PORT)" "$([[ $code == 000 ]] && echo true || echo false)" "http $code"
fi

# ── folder Q&A: real files MCP server on a generated fixture ─────────────────────────────────
WS="$WORK/ws"; PROJECT="$WS/project"
mkdir -p "$PROJECT/src/main/java/demo" "$PROJECT/target" "$PROJECT/.git" "$WS/other"
echo "# Orders demo" > "$PROJECT/README.md"
printf 'package demo;\n\nclass OrderService {\n    private final KafkaTemplate<String, String> kafka;\n}\n' \
    > "$PROJECT/src/main/java/demo/OrderService.java"
echo "class Generated { KafkaTemplate gen; }" > "$PROJECT/target/Generated.java"   # build dir: hidden
echo "DB_PASSWORD=hunter2" > "$PROJECT/.env"                                        # secret: denied
echo "private notes" > "$WS/other/notes.txt"                                         # allowed root, other folder
ELSEWHERE="$WORK/elsewhere"                                                          # outside the allowed roots
mkdir -p "$ELSEWHERE/docs" "$WORK/third/docs"
echo "approved folder content" > "$ELSEWHERE/docs/guide.md"
echo "API_KEY=sk-elsewhere" > "$ELSEWHERE/.env"
start_mcp() {   # start_mcp [true]: true lets the server accept folders the user approved in DevMate
    DEVMATE_FILESYSTEM_ALLOWED_ROOTS="$WS" DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS="${1:-false}" \
        DEVMATE_FILES_MCP_PORT=$MCP_PORT "$JAVA_HOME/bin/java" -jar "$MCP_JAR" >> "$WORK/mcp.log" 2>&1 &
    MCP_PID=$!
    wait_for_health "$MCP/actuator/health" "$MCP_PID"
}
open_folder() { curl -s -L -c "$JAR_COOKIES" -b "$JAR_COOKIES" "$BASE/folder" --data-urlencode "path=$1"; }
ask_api() {   # ask_api <folder> <question> → "<status> <body>"
    curl -s -w '\n%{http_code}' -X POST "$BASE/api/folder/ask" -H 'Content-Type: application/json' \
        -d "$(jq -n --arg f "$1" --arg q "$2" '{folder: $f, question: $q}')"
}
JAR_COOKIES="$WORK/cookies-folder"

echo; echo "Folder Q&A (files MCP server + fake model)"
p=$(curl -s -c "$JAR_COOKIES" -b "$JAR_COOKIES" "$BASE/")
result UI-35 "no folder open → 'Open a folder' link, plain chat" "$(both "$(has "$p" 'No folder open: plain chat.')" "$(has "$p" 'Open a folder')")"
p=$(curl -s -c "$JAR_COOKIES" -b "$JAR_COOKIES" "$BASE/folder")
result UI-36 "picker with files MCP down → 'not reachable' message" "$(has "$p" "Files MCP server is not reachable at $MCP/mcp")"

start_mcp
p=$(curl -s -c "$JAR_COOKIES" -b "$JAR_COOKIES" "$BASE/folder")
result UI-37 "picker suggests the server's allowed root" "$(both "$(has "$p" 'class="root-chip"')" "$(has "$p" "value=\"$WS\"")")"
p=$(open_folder "/etc")
result UI-38 "open /etc → ACCESS_DENIED outside the allowed roots" \
    "$(both "$(has "$p" 'ACCESS_DENIED: path is outside the allowed roots')" "$([[ $p != *'id="folder-path"'* ]] && echo true || echo false)")"
p=$(open_folder "project")
result UI-39 "open a relative path → asks for an absolute path" "$(has "$p" 'INVALID_ARGUMENT: enter an absolute path')"
p=$(open_folder "$WS/missing")
result UI-40 "open a missing folder → NOT_FOUND" "$(has "$p" 'NOT_FOUND: no such file or directory')"
p=$(open_folder "$PROJECT/README.md")
result UI-41 "open a file → NOT_A_DIRECTORY" "$(has "$p" 'NOT_A_DIRECTORY')"
p=$(open_folder "$PROJECT")
result UI-42 "open the project → folder bar, 2 entries (.env and target/ hidden)" \
    "$(both "$(has "$p" "id=\"folder-path\">$PROJECT<")" "$(has "$p" '2 entries')")"
result UI-43 "…and the folder empty state with code suggestions" "$(has "$p" 'Ask about this folder')"

p=$(send "Where is Kafka used? SEARCH KafkaTemplate")
result UI-44 "'SEARCH KafkaTemplate' → search_code finds OrderService.java line 4" \
    "$(both "$(has "$p" 'OrderService.java')" "$(has "$p" '&quot;line&quot;:4')")"
result UI-45 "…reply lists the tool call (Tools used (1): search_code)" \
    "$(both "$(has "$p" 'Tools used (1)')" "$(has "$p" 'search_code {&quot;query&quot;:&quot;KafkaTemplate&quot;}')")"
result UI-46 "…and never shows the build-dir copy (target/ excluded)" "$([[ $p != *'Generated.java'* ]] && echo true || echo false)"
p=$(send "READ src/main/java/demo/OrderService.java")
result UI-47 "'READ <relative path>' → read_file returns the file content" "$(has "$p" 'class OrderService')"
p=$(send "READ .env")
result UI-48 "'READ .env' → ACCESS_DENIED by the server's deny list, secret not shown" \
    "$(both "$(has "$p" 'ACCESS_DENIED: path is excluded by policy')" "$([[ $p != *hunter2* ]] && echo true || echo false)")"
p=$(send "READ ../other/notes.txt")
result UI-49 "'READ ../other/notes.txt' → refused: outside the selected folder" \
    "$(both "$(has "$p" 'ACCESS_DENIED: path is outside the selected folder')" "$([[ $p != *'private notes'* ]] && echo true || echo false)")"
p=$(send "READ /etc/passwd")
result UI-50 "'READ /etc/passwd' → refused, nothing read" \
    "$(both "$(has "$p" 'ACCESS_DENIED: path is outside the selected folder')" "$([[ $p != *'root:x:'* ]] && echo true || echo false)")"
start=$(date +%s)
p=$(send "LOOP")
took=$(( $(date +%s) - start ))
result UI-51 "model that never stops calling tools → stopped after 8 rounds (${took}s)" \
    "$(both "$(has "$p" 'I stopped after 8 rounds of tool calls')" "$(has "$p" 'Tools used (8)')")"
p=$(send "SYSTEM")
result UI-52 "system prompt names the folder and its entries, not hidden ones" \
    "$(both "$(both "$(has "$p" "project folder $PROJECT")" "$(has "$p" '<li>src/</li>')")" "$([[ $p != *'<li>.env'* ]] && echo true || echo false)")"
p=$(send "এই project-এ Kafka কোথায় ব্যবহার করা হয়েছে? SEARCH KafkaTemplate")
result UI-53 "Bangla question about the folder → tool answer" "$(both "$(has "$p" 'এই project-এ Kafka')" "$(has "$p" 'OrderService.java')")"

JAR_COOKIES="$WORK/cookies-folder-b"
p=$(page)
result UI-54 "a second browser has no folder open (per session)" "$(has "$p" 'No folder open')"
JAR_COOKIES="$WORK/cookies-folder"

r=$(ask_api "$PROJECT" "SEARCH KafkaTemplate")
result UI-55 "POST /api/folder/ask → 200 with reply and toolCalls" \
    "$([[ $(tail -1 <<<"$r") == 200 && $(head -n -1 <<<"$r" | jq -r '.toolCalls[0].name') == search_code ]] && echo true || echo false)" "$r"
codes="$(tail -1 <<<"$(ask_api /etc q)") $(tail -1 <<<"$(ask_api "$WS/missing" q)") $(tail -1 <<<"$(ask_api project q)") $(tail -1 <<<"$(ask_api "$PROJECT" ' ')")"
result UI-56 "API refusals: outside roots 403, missing 404, relative 400, blank question 400" \
    "$([[ $codes == '403 404 400 400' ]] && echo true || echo false)" "$codes"

kill "$MCP_PID" 2>/dev/null; wait "$MCP_PID" 2>/dev/null; MCP_PID=""
start=$(date +%s)
p=$(send "SEARCH KafkaTemplate while the server is down")
took=$(( $(date +%s) - start ))
result UI-57 "files MCP stopped → error message, quickly (${took}s)" \
    "$(both "$(has "$p" 'Files MCP server is not reachable')" "$([[ $took -le 10 ]] && echo true || echo false)")"
start_mcp
p=$(send "SEARCH KafkaTemplate")
result UI-58 "files MCP restarted → DevMate reconnects without a restart" "$(grep -c 'OrderService.java' <<<"$p" | grep -qv '^0$' && echo true || echo false)"
p=$(curl -s -L -c "$JAR_COOKIES" -b "$JAR_COOKIES" -X POST "$BASE/folder/close"; send "hello after close")
result UI-59 "'Close folder' → back to plain chat (no tools sent)" \
    "$(both "$(has "$p" 'No folder open')" "$(has "$p" 'Echo: hello after close')")"
leaks=$(grep -lE 'hunter2|KafkaTemplate<|class OrderService' "$WORK/app.log" "$WORK/mcp.log" 2>/dev/null)
result UI-60 "logs never contain file contents, secrets or tool arguments" "$([[ -z $leaks ]] && echo true || echo false)" "$leaks"

echo; echo "Folder outside the allowed roots: approval popup"
stop_mcp() { kill "$MCP_PID" 2>/dev/null; wait "$MCP_PID" 2>/dev/null; MCP_PID=""; }
allow() { curl -s -L -c "$JAR_COOKIES" -b "$JAR_COOKIES" "$BASE/folder/allow" --data-urlencode "path=$1"; }
p=$(open_folder "$ELSEWHERE")
result UI-61 "open a folder outside the roots → popup 'Allow DevMate to read this folder?'" \
    "$(both "$(has "$p" 'id="approve-dialog"')" "$(has "$p" "name=\"path\" value=\"$ELSEWHERE\"")")"
result UI-62 "…popup says read-only, secrets hidden, local model, until restart" \
    "$(both "$(both "$(has "$p" 'read and search')" "$(has "$p" 'stay hidden')")" "$(both "$(has "$p" 'stays on this machine')" "$(has "$p" 'until DevMate restarts')")")"
p=$(open_folder "$WS/missing")
result UI-63 "other refusals (NOT_FOUND) show no popup" "$([[ $p != *approve-dialog* ]] && echo true || echo false)"
p=$(allow "$ELSEWHERE")
result UI-64 "Allow while the server runs without allow-client-roots → explains the setting, nothing opened" \
    "$(both "$(has "$p" 'DEVMATE_FILESYSTEM_ALLOW_CLIENT_ROOTS=true')" "$([[ $p != *'id="folder-path"'* ]] && echo true || echo false)")"
stop_mcp; start_mcp true
p=$(allow "$ELSEWHERE")
result UI-65 "Allow with allow-client-roots=true → the folder opens" "$(has "$p" "id=\"folder-path\">$ELSEWHERE<")"
p=$(send "READ docs/guide.md")
result UI-66 "files in the approved folder can be read" "$(has "$p" 'approved folder content')"
p=$(send "READ .env")
result UI-67 "deny list still applies in the approved folder (.env refused)" \
    "$(both "$(has "$p" 'ACCESS_DENIED: path is excluded by policy')" "$([[ $p != *sk-elsewhere* ]] && echo true || echo false)")"
code=$(tail -1 <<<"$(ask_api "$WORK/third" q)")
result UI-68 "the JSON API can't approve: another outside folder → 403" "$([[ $code == 403 ]] && echo true || echo false)" "$code"
stop_mcp; start_mcp true
p=$(send "READ docs/guide.md")
result UI-69 "approval survives a files MCP restart (DevMate re-declares it)" "$(has "$p" 'approved folder content')"

echo; echo "Folder picker: click a folder instead of typing its path"
JAR_COOKIES="$WORK/cookies-picker"   # fresh browser: no chat history on the page
browse() { curl -s -c "$JAR_COOKIES" -b "$JAR_COOKIES" -G "$BASE/folder" ${1:+--data-urlencode "browse=$1"}; }
p=$(browse)
result UI-70 "picker starts in the browse-start folder with 'Open this folder'" \
    "$(both "$(has "$p" "id=\"browse-path\">$WS<")" "$(has "$p" 'id="open-current"')")"
result UI-71 "…lists its subfolders as links (other, project), sorted" \
    "$(both "$(has "$p" "href=\"/folder?browse=$WS/other\"")" "$([[ $p == *'>other<'*'>project<'* ]] && echo true || echo false)")"
p=$(browse "$PROJECT")
result UI-72 "browse into project → src and target listed; files and .git not" \
    "$(both "$(both "$(has "$p" '>src<')" "$(has "$p" '>target<')")" "$([[ $p != *README.md* && $p != *'>.git<'* && $p != *'.env'* ]] && echo true || echo false)")"
result UI-73 "…breadcrumb back to the parent, current folder marked" \
    "$(both "$(has "$p" "href=\"/folder?browse=$WS\"")" "$(has "$p" 'aria-current="location">project<')")"
result UI-74 "…'Open this folder' submits the browsed path" "$(has "$p" "name=\"path\" value=\"$PROJECT\"")"
p=$(open_folder "$PROJECT")
result UI-75 "clicking 'Open this folder' opens it (same as typing the path)" "$(has "$p" "id=\"folder-path\">$PROJECT<")"
p=$(browse "$WORK/third"); q=$(open_folder "$WORK/third")   # never approved
result UI-76 "a folder outside the roots can be browsed, and opening it asks for approval" \
    "$(both "$(has "$p" '>docs<')" "$(has "$q" 'id="approve-dialog"')")"
p=$(browse "$PROJECT/README.md")
result UI-77 "browsing a file → 'Not a folder', back at the start folder" \
    "$(both "$(has "$p" 'Not a folder')" "$(has "$p" "id=\"browse-path\">$WS<")")"
p=$(browse "relative/path")
result UI-78 "browsing a relative path → 'Enter an absolute path'" "$(has "$p" 'Enter an absolute path')"
p=$(browse "/")
result UI-79 "filesystem root: no parent link, folders listed" \
    "$(both "$([[ $p != *'id="dir-up"'* ]] && echo true || echo false)" "$(has "$p" '>etc<')")"
result UI-80 "'Type a path instead' is still available, collapsed" "$(both "$(has "$p" 'Type a path instead')" "$(has "$p" 'id="folder-input"')")"
leaks=$(grep -c 'approved folder content\|API_KEY' "$WORK/app.log")
result UI-81 "browsing never reads files (nothing from them in the page or log)" \
    "$([[ $leaks == 0 && $(browse "$ELSEWHERE") != *'approved folder content'* ]] && echo true || echo false)" "$leaks"

# ── optional: real model ──────────────────────────────────────────────────────────────────────
if [[ -n "${LIVE:-}" ]]; then
    LIVE_MODEL=${LIVE_MODEL:-llama3.2:3b}
    echo; echo "LIVE — real Ollama ($LIVE_MODEL); answers vary, so checks are loose"
    if curl -sf "${OLLAMA_BASE_URL:-http://localhost:11434}/api/tags" >/dev/null; then
        kill "$APP_PID"; wait "$APP_PID" 2>/dev/null
        DEVMATE_PORT=$PORT DEVMATE_OPEN_BROWSER=false DEVMATE_FILES_MCP_URL="$MCP/mcp" \
            "$JAVA_HOME/bin/java" -jar "$JAR" --devmate.llm.provider=ollama --devmate.llm.model="$LIVE_MODEL" \
            > "$WORK/app-live.log" 2>&1 &
        APP_PID=$!
        wait_for_health "$BASE/actuator/health" "$APP_PID"
        JAR_COOKIES="$WORK/cookies-live"
        p=$(send "What is the capital of France? Answer with one word.")
        result LIVE-1 "'capital of France' → answer mentions Paris" "$(grep -qi 'paris' <<<"$p" && echo true || echo false)"
        p=$(send "List 3 causes of Kafka consumer lag as a numbered list.")
        result LIVE-2 "'causes of Kafka consumer lag' → rendered list" "$(grep -qE '<ol>|<ul>' <<<"$p" && echo true || echo false)"
        p=$(send "Kafka কী? দুই বাক্যে বলো।")
        result LIVE-3 "Bangla question → non-empty answer" "$(grep -q 'class="message assistant"' <<<"$p" && echo true || echo false)"
        open_folder "$PROJECT" >/dev/null
        p=$(send "In which file is KafkaTemplate used? Use the search_code tool.")
        result LIVE-4 "folder question → the model calls a tool and names OrderService.java" \
            "$(both "$(has "$p" 'Tools used')" "$(has "$p" 'OrderService')")"
    else
        echo "  (skipped: no Ollama at ${OLLAMA_BASE_URL:-http://localhost:11434})"
    fi
fi

summary "the web UI and chat work"
if [[ -n "${KEEP:-}" ]]; then
    echo "KEEP=1: app still running at $BASE/ (pid $APP_PID), fake model pid $FAKE_PID, files MCP pid $MCP_PID"
    echo "        fixture folder to open in the UI: $PROJECT"
fi
[[ $FAILED -eq 0 ]]
