#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────
# DevMate web UI / chat acceptance check: do the messages you send from the UI work?
#
# Starts the real devmate-client jar against a deterministic fake Ollama (scripts/support/
# fake_ollama.py), then sends every example message from docs/testing/UI-chat-test-cases.md
# through the web UI exactly like a browser (form POST, redirect, session cookie) and checks
# the rendered page. Prints PASS/FAIL per case.
#
#   scripts/check-client.sh             # deterministic run (fake model), ~30 s
#   LIVE=1 scripts/check-client.sh      # also send the LIVE cases to your real Ollama (llama3.2:3b)
#   KEEP=1 scripts/check-client.sh      # leave the app running on the fake model to click around
#
# Exit code 0 = all cases passed. Needs: JDK 25, curl, jq, python3.
# ─────────────────────────────────────────────────────────────────────────────────────────────
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT_DIR/scripts/support/common.sh"

PORT=${PORT:-18410}
FAKE_PORT=$((PORT + 1))
BASE="http://127.0.0.1:${PORT}"
FAKE="http://127.0.0.1:${FAKE_PORT}"
JAR="$ROOT_DIR/devmate-client/target/devmate-client-0.1.0-SNAPSHOT.jar"
WORK=$(mktemp -d -t devmate-ui-XXXX)
APP_PID=""; FAKE_PID=""

cleanup() {
    [[ -n "${KEEP:-}" ]] && return
    [[ -n "$APP_PID" ]] && kill "$APP_PID" 2>/dev/null
    [[ -n "$FAKE_PID" ]] && kill "$FAKE_PID" 2>/dev/null
    rm -rf "$WORK"
}
trap cleanup EXIT

require_tools curl jq python3
JAVA_HOME=$(pick_jdk)
[[ -n "$JAVA_HOME" ]] || { echo "JDK 25+ not found: set JAVA_HOME to a JDK 25"; exit 2; }
if [[ ! -f "$JAR" ]]; then
    echo "Building devmate-client…"
    (cd "$ROOT_DIR" && JAVA_HOME="$JAVA_HOME" ./mvnw -B -q -pl devmate-client -am package -DskipTests) || exit 2
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
DEVMATE_PORT=$PORT DEVMATE_OPEN_BROWSER=false OLLAMA_BASE_URL="$FAKE" \
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

# ── optional: real model ──────────────────────────────────────────────────────────────────────
if [[ -n "${LIVE:-}" ]]; then
    LIVE_MODEL=${LIVE_MODEL:-llama3.2:3b}
    echo; echo "LIVE — real Ollama ($LIVE_MODEL); answers vary, so checks are loose"
    if curl -sf "${OLLAMA_BASE_URL:-http://localhost:11434}/api/tags" >/dev/null; then
        kill "$APP_PID"; wait "$APP_PID" 2>/dev/null
        DEVMATE_PORT=$PORT DEVMATE_OPEN_BROWSER=false \
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
    else
        echo "  (skipped: no Ollama at ${OLLAMA_BASE_URL:-http://localhost:11434})"
    fi
fi

summary "the web UI and chat work"
if [[ -n "${KEEP:-}" ]]; then
    echo "KEEP=1: app still running at $BASE/ (pid $APP_PID), fake model pid $FAKE_PID"
fi
[[ $FAILED -eq 0 ]]
