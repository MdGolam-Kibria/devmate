#!/usr/bin/env python3
"""
Deterministic misbehaving MCP server (Streamable HTTP, JSON responses), used by scripts/check-mcp-client.sh to drive
every failure category of DevMate's MCP client against the packaged jar. Standard library only. Implements just
enough of MCP: initialize (issues an Mcp-Session-Id), notifications (202), tools/list, tools/call, DELETE; GET (the
optional SSE stream) is 405.

Tools (all readOnlyHint=true except fake_delete):
  fake_echo {message: string, required}  -> "echo: <message>"
  fake_slow                               -> answers after 3 s (DevMate is configured with a 1 s request timeout)
  fake_crash                              -> JSON-RPC error -32603 with a multi-line, ANSI-coloured message
  fake_strict                             -> JSON-RPC error -32602 "depth must be <= 8"
  fake_http500                            -> HTTP 500
  fake_big                                -> 5 MB of text (DevMate caps responses at 4 MB)
  fake_delete                             -> readOnlyHint=false, destructiveHint=true: DevMate must never call it
  search_code                             -> same name as the files server's tool: DevMate must never call this one

Test hooks:
  GET /__calls   -> {"calls": {tool: count}, "initializes": n, "sessions": n}
  GET /__expire  -> forgets every session: the next request with an old Mcp-Session-Id gets 404

Usage: fake_mcp.py <port>
"""
import json
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOOLS = [
    {"name": "fake_echo", "description": "Echo a message",
     "inputSchema": {"type": "object", "properties": {"message": {"type": "string"}}, "required": ["message"]}},
    {"name": "fake_slow", "description": "Answers after 3 seconds", "inputSchema": {"type": "object"}},
    {"name": "fake_crash", "description": "Fails with a JSON-RPC internal error", "inputSchema": {"type": "object"}},
    {"name": "fake_strict", "description": "Rejects its params", "inputSchema": {"type": "object"}},
    {"name": "fake_http500", "description": "Answers HTTP 500", "inputSchema": {"type": "object"}},
    {"name": "fake_big", "description": "Answers 5 MB", "inputSchema": {"type": "object"}},
    {"name": "fake_delete", "description": "Deletes everything", "inputSchema": {"type": "object"},
     "annotations": {"readOnlyHint": False, "destructiveHint": True}},
    {"name": "search_code", "description": "Shadowed by the files server", "inputSchema": {"type": "object"}},
]
for tool in TOOLS:
    tool.setdefault("annotations", {"readOnlyHint": True, "destructiveHint": False})

lock = threading.Lock()
calls = {}
sessions = set()
initializes = 0


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # keep the check script output clean
        pass

    def _send(self, status, body=None, headers=None, content_type="application/json"):
        data = b"" if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
        self.send_response(status)
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        if data:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        global sessions
        if self.path == "/__calls":
            with lock:
                self._send(200, {"calls": calls, "initializes": initializes, "sessions": len(sessions)})
        elif self.path == "/__expire":
            with lock:
                sessions = set()
            self._send(200, {"expired": True})
        else:
            self._send(405)

    def do_DELETE(self):
        with lock:
            sessions.discard(self.headers.get("Mcp-Session-Id"))
        self._send(200)

    def do_POST(self):
        global initializes
        message = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        method, request_id = message.get("method"), message.get("id")
        session = self.headers.get("Mcp-Session-Id")
        if method != "initialize":
            with lock:
                known = session in sessions
            if not known:
                self._send(404, {"jsonrpc": "2.0", "id": request_id,
                                 "error": {"code": -32001, "message": "Session not found"}})
                return
        if request_id is None:  # notification
            self._send(202)
            return
        if method == "initialize":
            new_session = str(uuid.uuid4())
            with lock:
                sessions.add(new_session)
                initializes += 1
            version = message.get("params", {}).get("protocolVersion", "2025-06-18")
            self._send(200, result(request_id, {
                "protocolVersion": version,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "fake-mcp", "version": "1.0"}}), {"Mcp-Session-Id": new_session})
        elif method == "tools/list":
            self._send(200, result(request_id, {"tools": TOOLS}))
        elif method == "tools/call":
            self.call_tool(request_id, message.get("params", {}))
        elif method == "ping":
            self._send(200, result(request_id, {}))
        else:
            self._send(200, error(request_id, -32601, "Method not found: " + str(method)))

    def call_tool(self, request_id, params):
        name, arguments = params.get("name"), params.get("arguments") or {}
        with lock:
            calls[name] = calls.get(name, 0) + 1
        if name == "fake_echo":
            self._send(200, text(request_id, "echo: " + str(arguments.get("message"))))
        elif name == "fake_slow":
            time.sleep(3)
            self._send(200, text(request_id, "finally"))
        elif name == "fake_crash":
            self._send(200, error(request_id, -32603, "fake failure\nsecond line \u001b[31mred"))
        elif name == "fake_strict":
            self._send(200, error(request_id, -32602, "depth must be <= 8"))
        elif name == "fake_http500":
            self._send(500, b"<html>boom</html>", content_type="text/html")
        elif name == "fake_big":
            self._send(200, text(request_id, "x" * (5 * 1024 * 1024)))
        elif name in ("fake_delete", "search_code"):
            self._send(200, text(request_id, "SHOULD NOT HAVE BEEN CALLED"))
        else:
            self._send(200, error(request_id, -32602, "Unknown tool: invalid_tool_name"))


def result(request_id, body):
    return {"jsonrpc": "2.0", "id": request_id, "result": body}


def error(request_id, code, message):
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def text(request_id, value):
    return result(request_id, {"content": [{"type": "text", "text": value}], "isError": False})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
