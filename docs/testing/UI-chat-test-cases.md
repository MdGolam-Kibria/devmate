# Web UI test cases: chat messages

Example messages to send from the DevMate web UI (`http://localhost:8083/`) and what must happen for each. Every
case is automated in [`scripts/check-client.sh`](../../scripts/check-client.sh), which drives the **real app jar**
exactly like a browser: form POST, redirect, session cookie, rendered HTML.

```bash
./scripts/check-client.sh            # 34 deterministic cases against a fake model (~30 s)
LIVE=1 ./scripts/check-client.sh     # + 3 cases against your real Ollama (llama3.2:3b)
KEEP=1 ./scripts/check-client.sh     # leave the app running on the fake model and try the messages yourself
```

Last run: **2026-10-05: 34/34 PASS, and with `LIVE=1` 37/37 PASS** (JDK 25, Ollama `llama3.2:3b`).
Negative check: with `DEVMATE_ADDRESS=0.0.0.0 DEVMATE_LLM_MAX_ATTEMPTS=6` the script correctly **fails** UI-34
(reachable from the LAN) and UI-23 (error took 23 s).

## Why a fake model?

Real models answer differently every time, so a check like "the reply contains a table" can't be reliable. The script
points DevMate at `scripts/support/fake_ollama.py`, which speaks Ollama's `/api/chat` and answers **by keyword**:

| Your message contains | Fake model replies |
|---|---|
| `MARKDOWN` | Bold text, a numbered list, `inline code`, a Java code block and a table |
| `XSS` | `<script>alert('xss')</script>`, a `javascript:` link and `<img onerror>` (DevMate must neutralise all of it) |
| `FAIL` | HTTP 500 (provider error) |
| anything else | `Echo: <your message>` |

The app, UI, validation, rendering, sessions and error handling under test are all real. Only the model is replaced.
The `LIVE` cases then confirm the same path works with a real model.

---

## Example messages

Type these in the UI box and press **Enter**. The "Expected (fake model)" column is what the script checks; the last
column is what to look for when you try it by hand with a real model.

### Normal messages

| ID | Message to send | Expected (fake model) | With a real model |
|---|---|---|---|
| UI-07 | `Hello` | Your bubble "Hello" on the right; reply "Echo: Hello" on the left | A greeting |
| UI-08 | *(same)* | Under the reply: `fake-model · 12 in / 7 out tokens · N ms` | `llama3.2:3b · … tokens · … ms` |
| UI-09 | `এই project-এ Kafka কোথায় ব্যবহার করা হয়েছে?` | Bangla text shown intact in both bubbles | An answer (no code access yet: that's M3) |
| UI-10 | `Price < 100 & qty > 5 😀 "quotes"` | Shown exactly as typed; `<`, `&`, `>`, `"` are escaped in HTML, emoji kept | — |
| UI-11 | three lines: `line one` ⏎ `line two` ⏎ `line three` (Shift+Enter between) | Line breaks preserved in your bubble | — |
| UI-17 | *(after the above)* | All messages still listed, oldest at the top | — |

### Formatting (Markdown)

| ID | Message to send | Expected (fake model) | With a real model |
|---|---|---|---|
| UI-12 | `MARKDOWN: list 3 causes of Kafka consumer lag` | **Bold** text, a numbered list, `max.poll.records` as code, a grey Java code block | `List 3 causes of Kafka consumer lag as a numbered list with bold titles` gives a rendered list |
| UI-13 | *(same)* | A rendered table (`Cause` / `Fix`) | `Compare Kafka and RabbitMQ in a table` |

### Safety: none of these may run code in your browser

| ID | Message to send | Expected |
|---|---|---|
| UI-14 | `<script>alert("you")</script> <b>bold?</b>` | Shown as literal text; no popup, nothing bold |
| UI-15 | `XSS please` | The model's `<script>`, `<img onerror>` and `javascript:` link appear as plain text: no popup, no clickable `javascript:` link |
| UI-16 | *(same)* | The text is still readable (escaped, not deleted) |

### Input validation: must NOT reach the model

| ID | Message to send | Expected |
|---|---|---|
| UI-18 | *(only spaces)* | Red note "Message must be 1–8000 characters."; nothing added to the chat |
| UI-19 | 8001 characters (paste a long text) | Same note; the browser box also stops at 8000 |
| UI-20 | *(both above)* | Model call counter unchanged |
| UI-21 | exactly 8000 characters | Accepted and answered |

### Errors

| ID | Message / action | Expected |
|---|---|---|
| UI-22 | `FAIL: simulate a provider error` | Red error bubble: `LLM provider 'ollama' failed: HTTP 500 …` |
| UI-23 | *(same)* | The error appears within a few seconds (retries are capped at 2), not minutes |
| UI-24 | Stop the model (`ollama stop` / fake killed), send `Hello while the model is down` | Red error bubble; your message still shown; page keeps working |
| UI-25 | Start the model again, send `Hello again` | Normal answer, no DevMate restart needed |

### Session & navigation

| ID | Action | Expected |
|---|---|---|
| UI-26 | Press F5 after a reply | Page reloads; the question is **not** sent again |
| UI-27 | Open the UI in a second browser / private window | Its own empty chat ("Ask DevMate") |
| UI-28 | Click **New chat** | Conversation cleared; empty state shown |
| UI-29 | DevTools → Application → Cookies | `JSESSIONID` is `HttpOnly`, `SameSite=Strict` |
| UI-30 | Watch the address bar after sending | Always `/`, never `;jsessionid=…` |

### Page & startup

| ID | Check | Expected |
|---|---|---|
| UI-01 | `curl localhost:8083/actuator/health` | `{"status":"UP",…}` |
| UI-02 | Open `/` | Title "DevMate"; badge shows provider / model |
| UI-03 | Empty chat | "Ask DevMate" plus 3 suggestion buttons (clicking one sends it) |
| UI-04 | Footer with Ollama | "Runs on your local Ollama; nothing leaves this machine." (Anthropic: "Messages are sent to anthropic.") |
| UI-05 | `/css/devmate.css` | 200 |
| UI-06 | Startup log | `DevMate UI ready at http://localhost:<port>/` (and the browser opens unless `DEVMATE_OPEN_BROWSER=false`) |
| UI-34 | From another machine: `http://<your-LAN-IP>:8083/` | Connection refused (bound to 127.0.0.1) |

### JSON API (same model, for scripts)

| ID | Request | Expected |
|---|---|---|
| UI-31 | `POST /api/chat {"message":"Hello API"}` | `{"reply":"Echo: Hello API","provider":"ollama","model":"fake-model","inputTokens":12,…}` |
| UI-32 | `POST /api/chat {"message":" "}` | 400 `INVALID_REQUEST` |
| UI-33 | `POST /api/chat {"message":"FAIL"}` | 502 `LLM_UNAVAILABLE` |

### LIVE: real Ollama (`LIVE=1`)

Real answers vary, so these checks are deliberately loose.

| ID | Message | Pass if |
|---|---|---|
| LIVE-1 | `What is the capital of France? Answer with one word.` | Reply mentions "Paris" |
| LIVE-2 | `List 3 causes of Kafka consumer lag as a numbered list.` | Reply renders as a list |
| LIVE-3 | `Kafka কী? দুই বাক্যে বলো।` | A non-empty answer bubble |

Small local models (1.5–3B) can answer factually wrong even when these pass. Quality isn't what's being tested here;
the wiring is.

---

## Found while writing these cases

- **Spring AI's default retry made provider errors hang for minutes** (10 attempts, 2 s × 5 backoff). It's now
  capped in `application.yml` (`DEVMATE_LLM_MAX_ATTEMPTS`, default 2, 1–5 s backoff), and UI-23 guards it.
- `javascript:` appearing as **text** is harmless. The check looks for it inside `href`/`src` attributes, which is
  where it would be dangerous.

## Relation to the Maven tests

`ChatPageIT`, `ChatControllerIT` and `MarkdownRendererTest` cover the same behaviour inside a test context with a
mocked `LlmClient`. This script checks the **packaged app as you run it**, with a real HTTP model server.
Both must pass:

```bash
./mvnw -pl devmate-client verify && ./scripts/check-client.sh
```
