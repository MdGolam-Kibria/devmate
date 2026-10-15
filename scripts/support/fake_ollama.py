#!/usr/bin/env python3
"""
Deterministic stand-in for Ollama's /api/chat, used by scripts/check-client.sh so UI test cases give the same
answer every run. Standard library only.

Reply chosen from the last user message:
  contains "MARKDOWN"  -> a reply with bold, a numbered list, inline code, a code block and a table
  contains "XSS"       -> a reply carrying <script> and a javascript: link (must be neutralised by DevMate)
  contains "FAIL"      -> HTTP 500 (provider error)
  anything else        -> "Echo: <message>"

Tool calling (only when DevMate sent tool definitions, i.e. a folder is open). Keyword in the last user message:
  "SEARCH <text>"      -> calls search_code {"query": text}
  "READ <path>"        -> calls read_file {"path": path}
  "LIST"               -> calls list_files {}
  "TREE"               -> calls folder_tree {"depth": 8}
  "FIND <glob>"        -> calls find_files {"glob": glob}
  "OVERVIEW"           -> calls folder_overview {}
  "RANGE <path> <a> <b>" -> calls read_file {"path": path, "startLine": a, "endLine": b}
  "LOOP"               -> calls list_files on every round (DevMate must stop it)
  "CALL <tool> <json>" -> calls any tool by name with the given JSON arguments (even one DevMate didn't offer)
Without tool definitions too:
  "SYSTEM"             -> replies with the system prompt it received
  "TOOLS"              -> replies "Tools offered: <names>" (or "none"): the tool definitions DevMate sent
  "HISTORY"            -> replies "History: <n> messages: role: text | …": the earlier turns DevMate sent
Once tool results are in the conversation it replies "From the tools:" followed by each result (first 600 chars).

GET /__count returns how many /api/chat calls were received (to prove a message was NOT sent to the model).
GET /__last returns the last /api/chat request body (for debugging the request format).

Usage: fake_ollama.py <port>
"""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MARKDOWN_REPLY = """Here are **3 causes** of consumer lag:

1. **Slow processing** in the listener
2. `max.poll.records` set too high
3. Too few partitions

```java
consumer.poll(Duration.ofMillis(100));
```

| Cause | Fix |
|---|---|
| Slow handler | Scale consumers |"""

XSS_REPLY = "<script>alert('xss')</script> Click [here](javascript:alert(1)) <img src=x onerror=alert(2)>"

calls = 0
last_request = {}
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # keep the check script output clean
        pass

    def _json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/__count":
            self._json(200, {"calls": calls})
        elif self.path == "/__last":
            self._json(200, last_request)
        elif self.path in ("/", "/api/tags"):
            self._json(200, {"models": [{"name": "fake-model"}]})
        else:
            self._json(404, {"error": "not found"})

    def _body(self):
        # The JDK/Netty HTTP clients may send the body chunked, without Content-Length.
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            body = b""
            while True:
                size = int(self.rfile.readline().strip().split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    return body
                body += self.rfile.read(size)
                self.rfile.readline()
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def do_POST(self):
        global calls, last_request
        request = json.loads(self._body() or b"{}")
        if self.path != "/api/chat":
            self._json(404, {"error": "not found"})
            return
        with lock:
            calls += 1
            last_request = request
        user = next((m.get("content", "") for m in reversed(request.get("messages", []))
                     if m.get("role") == "user"), "")
        context_reply = self._context_step(request, user)
        if context_reply is not None:
            self._json(200, context_reply)
            return
        tool_reply = self._tool_step(request, user)
        if tool_reply is not None:
            self._json(200, tool_reply)
            return
        if "FAIL" in user:
            self._json(500, {"error": "fake model crashed"})
            return
        if "MARKDOWN" in user:
            reply = MARKDOWN_REPLY
        elif "XSS" in user:
            reply = XSS_REPLY
        else:
            reply = "Echo: " + user
        self._json(200, {
            "model": request.get("model", "fake-model"),
            "created_at": "2026-10-05T00:00:00Z",
            "message": {"role": "assistant", "content": reply},
            "done": True,
            "done_reason": "stop",
            "total_duration": 1000000,
            "prompt_eval_count": 12,
            "eval_count": 7,
        })


    def _context_step(self, request, user):
        """Replies that describe what DevMate sent (system prompt, tools, history), or None."""
        words = user.split()
        messages = request.get("messages", [])
        if "SYSTEM" in words:
            system = next((m.get("content", "") for m in messages if m.get("role") == "system"), "")
            return self._reply(request, "System prompt was: " + system)
        if "TOOLS" in words:
            names = [t.get("function", {}).get("name", "?") for t in request.get("tools") or []]
            return self._reply(request, "Tools offered: " + (", ".join(names) if names else "none"))
        if "HISTORY" in words:
            last_user = max(i for i, m in enumerate(messages) if m.get("role") == "user")
            earlier = [m for m in messages[:last_user] if m.get("role") in ("user", "assistant")]
            return self._reply(request, "History: %d messages: %s" % (
                len(earlier), " | ".join(m["role"] + ": " + m.get("content", "") for m in earlier)))
        return None

    def _tool_step(self, request, user):
        """A tool-calling reply, or None to fall through to the plain replies."""
        if not request.get("tools"):
            return None
        messages = request.get("messages", [])
        last_user = max(i for i, m in enumerate(messages) if m.get("role") == "user")
        results = [m.get("content", "") for m in messages[last_user + 1:] if m.get("role") == "tool"]
        words = user.split()
        call = None
        if "LOOP" in user:
            call = ("list_files", {})
        elif not results and "CALL" in words and words.index("CALL") + 2 < len(words):
            i = words.index("CALL")
            call = (words[i + 1], json.loads(" ".join(words[i + 2:])))
        elif not results and "SEARCH" in words and words.index("SEARCH") + 1 < len(words):
            call = ("search_code", {"query": words[words.index("SEARCH") + 1]})
        elif not results and "READ" in words and words.index("READ") + 1 < len(words):
            call = ("read_file", {"path": words[words.index("READ") + 1]})
        elif not results and "LIST" in words:
            call = ("list_files", {})
        elif not results and "TREE" in words:
            call = ("folder_tree", {"depth": 8})
        elif not results and "FIND" in words and words.index("FIND") + 1 < len(words):
            call = ("find_files", {"glob": words[words.index("FIND") + 1]})
        elif not results and "OVERVIEW" in words:
            call = ("folder_overview", {})
        elif not results and "RANGE" in words and words.index("RANGE") + 3 < len(words):
            i = words.index("RANGE")
            call = ("read_file", {"path": words[i + 1], "startLine": int(words[i + 2]), "endLine": int(words[i + 3])})
        if call:
            return self._reply(request, "", [{"function": {"name": call[0], "arguments": call[1]}}])
        if results:
            return self._reply(request, "From the tools:\n\n" + "\n\n".join(r[:600] for r in results))
        return None

    @staticmethod
    def _reply(request, content, tool_calls=None):
        message = {"role": "assistant", "content": content}
        if tool_calls:
            message["tool_calls"] = tool_calls
        return {
            "model": request.get("model", "fake-model"),
            "created_at": "2026-10-05T00:00:00Z",
            "message": message,
            "done": True,
            "done_reason": "stop",
            "total_duration": 1000000,
            "prompt_eval_count": 12,
            "eval_count": 7,
        }


if __name__ == "__main__":
    port = int(sys.argv[1])
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
