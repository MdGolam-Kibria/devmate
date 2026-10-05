#!/usr/bin/env bash
# Shared helpers for scripts/check-*.sh. Source it; don't run it.

PASSED=0
FAILED=0

green() { printf '\033[32m%s\033[0m' "$1"; }
red()   { printf '\033[31m%s\033[0m' "$1"; }

# result <id> <description> <true|false> [actual-on-failure]
result() {
    if [[ "$3" == true ]]; then
        PASSED=$((PASSED + 1)); printf '  %s  %-6s %s\n' "$(green PASS)" "$1" "$2"
    else
        FAILED=$((FAILED + 1)); printf '  %s  %-6s %s\n' "$(red FAIL)" "$1" "$2"
        [[ -n "${4:-}" ]] && printf '                actual: %s\n' "${4:0:300}"
    fi
}

# Prints a JDK 25+ home: $JAVA_HOME if it qualifies, else one under ~/.jdks or /usr/lib/jvm.
# (On this machine the shell's JAVA_HOME is Java 17, which can't run DevMate.)
java_major() { "$1/bin/java" -version 2>&1 | grep -oE 'version "[0-9]+' | grep -oE '[0-9]+$'; }
pick_jdk() {
    local candidate
    for candidate in "${JAVA_HOME:-}" "$HOME"/.jdks/*25* /usr/lib/jvm/*25*; do
        [[ -n "$candidate" && -x "$candidate/bin/java" ]] || continue
        [[ $(java_major "$candidate") -ge 25 ]] 2>/dev/null && { echo "$candidate"; return; }
    done
}

require_tools() {
    local tool
    for tool in "$@"; do
        command -v "$tool" >/dev/null || { echo "missing prerequisite: $tool"; exit 2; }
    done
}

# wait_for_health <url> <pid>: true once the URL returns 2xx, false if the process dies or 30s pass
wait_for_health() {
    for _ in $(seq 1 60); do
        curl -sf "$1" >/dev/null && return 0
        kill -0 "$2" 2>/dev/null || return 1
        sleep 0.5
    done
    return 1
}

summary() {  # summary <what is working>
    echo
    local total=$((PASSED + FAILED))
    if [[ $FAILED -eq 0 ]]; then
        echo "$(green "ALL $total CASES PASSED") — $1."
    else
        echo "$(red "$FAILED of $total CASES FAILED")"
    fi
}
