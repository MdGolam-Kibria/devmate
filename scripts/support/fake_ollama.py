#!/usr/bin/env python3
"""
Deterministic stand-in for Ollama's /api/chat, used by scripts/check-client.sh so UI test cases give the same
answer every run. Standard library only.

Reply chosen from the last user message:
  contains "MARKDOWN"  -> a reply with bold, a numbered list, inline code, a code block and a table
  contains "XSS"       -> a reply carrying <script> and a javascript: link (must be neutralised by DevMate)
  contains "FAIL"      -> HTTP 500 (provider error)
  anything else        -> "Echo: <message>"

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


if __name__ == "__main__":
    port = int(sys.argv[1])
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
