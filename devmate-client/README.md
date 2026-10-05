# devmate-client

The DevMate application you interact with: a **chat web UI** and **JSON API** in front of a configurable LLM
(Anthropic Claude or local Ollama). It runs on **http://localhost:8083/** and opens in your browser on startup.

> Later milestones turn this module into the MCP client (M2) and the agent (M3). Today it chats with the model
> directly and does **not** use MCP tools yet.

---

## Features implemented

| Feature | Where | Notes |
|---|---|---|
| Chat web UI | `ui/ChatPageController`, `templates/chat.html`, `static/css/devmate.css` | Thymeleaf, server-rendered, works without JS; light/dark |
| Markdown replies | `ui/MarkdownRenderer` | Lists, code, tables; raw HTML escaped, `javascript:` links removed |
| Conversation history | `ui/ChatHistory` | Per browser session, last 100 messages, "New chat" clears it |
| Open browser on startup | `ui/BrowserLauncher` | `xdg-open` / `open` / `rundll32`; off in tests and headless |
| JSON chat API | `llm/ChatController` | `POST /api/chat` → reply + model + tokens + time |
| Any LLM by config | `llm/LlmClient`, `llm/SpringAiLlmClient`, `application.yml` | Anthropic block active, Ollama block commented |
| API-key check | `llm/LlmCredentialsCheck` | Anthropic without a key stops startup with a clear message |
| Request ids | `web/RequestIdFilter` | `X-Request-Id` on every response, `requestId` in every log line |
| Health | Actuator | `/actuator/health`, `/liveness`, `/readiness`, `/info`; nothing else exposed |
| Safe defaults | `application.yml` | Binds `127.0.0.1`, cookie-only `HttpOnly` + `SameSite=Strict` sessions, no stack traces |

---

## How it works

### A message from the UI

```text
Browser                     devmate-client                                         LLM
───────                     ──────────────                                         ───
POST /chat  message=…  ──►  RequestIdFilter     adds X-Request-Id, MDC requestId
                            ChatPageController  validate 1–8000 chars
                              │ ChatHistory.add(USER, question)
                              ▼
                            LlmClient.chat(LlmRequest)        ← interface, no provider types
                              │
                            SpringAiLlmClient                 ← the only adapter
                              │ builds Prompt(SystemMessage?, UserMessage)
                              ▼
                            Spring AI ChatModel  ─────────────────────────────►  Anthropic API
                            (AnthropicChatModel or OllamaChatModel,               or Ollama :11434
                             whichever spring.ai.model.chat selects)  ◄────────  answer + token usage
                              │
                            LlmResponse(text, model, inputTokens, outputTokens)
                              │
                            MarkdownRenderer.toSafeHtml(text)
                            ChatHistory.add(ASSISTANT, text, html, "model · tokens · ms")
                              │
◄── 302 redirect to /  ─────┘          (post/redirect/get: refresh never re-sends)
GET /  ───────────────────► chat.html renders ChatHistory
                              user + error text: th:text  (escaped)
                              assistant html:    th:utext (already sanitised)
```

If the provider fails, `SpringAiLlmClient` throws `LlmException` with the root cause ("Connection refused",
"401 …"). The UI shows it as a red error message; the JSON API returns `502 LLM_UNAVAILABLE`.

### How the provider switch works

`application.yml` has one place you edit and one derived "wiring" section:

```yaml
devmate:
  llm:
    provider: anthropic                              # ← you change these two lines
    model: ${DEVMATE_LLM_MODEL:claude-sonnet-5-5}
    # provider: ollama
    # model: ${DEVMATE_LLM_MODEL:llama3.2:3b}
---
spring:
  ai:
    model:
      chat: ${devmate.llm.provider}                  # Spring AI activates ONLY this provider's ChatModel
    anthropic.chat.model: ${devmate.llm.model}
    ollama.chat.model:    ${devmate.llm.model}
```

Both provider libraries are on the classpath, but `spring.ai.model.chat` decides which `ChatModel` bean exists.
`SpringAiLlmClient` receives whichever one that is, so no Java code knows or cares which provider is active.
Adding another provider (OpenAI, Gemini, …) means adding its Spring AI starter and another commented block.

### How the browser opens

`BrowserLauncher` listens for `ApplicationReadyEvent`, which fires after the server is accepting requests. It reads
the real port, logs `DevMate UI ready at http://localhost:<port>/` and, if `devmate.ui.open-browser` is true and a
desktop session exists (`DISPLAY`/`WAYLAND_DISPLAY` on Linux), runs the OS "open URL" command on a virtual thread.
A failure only logs a message; startup is never affected.

---

## Run

```bash
export JAVA_HOME=~/.jdks/ms-25.0.4.1
./mvnw -pl devmate-client -am package -DskipTests        # from the repo root
export ANTHROPIC_API_KEY=sk-ant-…                        # only for the Anthropic block
java -jar devmate-client/target/devmate-client-0.1.0-SNAPSHOT.jar
```

| Setting | Env var | Default |
|---|---|---|
| Port | `DEVMATE_PORT` | `8083` |
| Listen address | `DEVMATE_ADDRESS` | `127.0.0.1` |
| Open browser | `DEVMATE_OPEN_BROWSER` | `true` |
| Model (either provider) | `DEVMATE_LLM_MODEL` | from the active block |
| Ollama URL | `OLLAMA_BASE_URL` | `http://localhost:11434` |
| Anthropic key | `ANTHROPIC_API_KEY` | none |
| Attempts on provider error | `DEVMATE_LLM_MAX_ATTEMPTS` | `2` (Spring AI default 10 would hang minutes) |

IntelliJ: run `dev.devmate.client.DevMateClientApplication` with SDK `ms-25.0.4.1`; set env vars in the run configuration.

## Use

- **UI:** type a question, press Enter. The badge shows provider/model; each answer shows tokens and time.
- **API:**
  ```bash
  curl -s -X POST localhost:8083/api/chat -H 'Content-Type: application/json' -d '{"message":"What is Kafka?"}'
  ```
- **Health:** `curl localhost:8083/actuator/health`

---

## Test

**Do the messages I send from the UI work? One command:**

```bash
./scripts/check-client.sh          # 34 example messages/actions → "ALL 34 CASES PASSED"
LIVE=1 ./scripts/check-client.sh   # + 3 against your real Ollama
```

The example messages and expected results are in
[docs/testing/UI-chat-test-cases.md](../docs/testing/UI-chat-test-cases.md). Try them by hand in the UI too.

Maven suites:

```bash
./mvnw -pl devmate-client verify                       # all client tests (≈55)
./mvnw -pl devmate-client test -Dtest=MarkdownRendererTest
./mvnw -pl devmate-client verify -Dit.test=ChatPageIT
DEVMATE_LIVE_OLLAMA=true ./mvnw -pl devmate-client verify -Dit.test=OllamaLiveIT   # real Ollama call
```

| Test | Proves |
|---|---|
| `ChatPageIT` | UI over real HTTP with a cookie-keeping client: send → answer shown, history per session, clear, HTML escaped, Markdown rendered, validation, error display, `SameSite=Strict`, no `jsessionid` in URLs |
| `ChatControllerIT` | `/api/chat` returns 200 / 400 / 502 with the right JSON |
| `LlmProviderSwitchIT` | Anthropic config gives `AnthropicChatModel`, Ollama config gives `OllamaChatModel`; env model override works; missing key stops startup |
| `SpringAiLlmClientTest` | Prompt order, blank system prompt dropped, token mapping, provider errors become `LlmException` |
| `MarkdownRendererTest` | Formatting renders; `<script>`, `<img onerror>`, `<iframe>`, `javascript:` neutralised |
| `BrowserLauncherTest` | Right command per OS; nothing on headless Linux |
| `ActuatorHealthIT` | Health/probes UP; `env`, `beans`, `heapdump` are 404 |
| `RequestIdFilterTest` | Valid ids reused; malformed ids replaced (no log injection) |
| `OllamaLiveIT` | Opt-in real round trip to local Ollama |

Normal tests never call a real model: they replace `LlmClient` with `@MockitoBean`, and a dummy Anthropic key in
`src/test/resources/application.properties` lets the context start offline. Browser auto-open is disabled there too.

**Manual check:** start the app, the browser opens, ask "List 3 causes of Kafka consumer lag". You should get a
rendered numbered list with tokens/time underneath. Then ask it to reply with `<script>alert(1)</script>`: you see
the text and no popup.

More: [developer guide](../docs/DEVELOPER-GUIDE.md) · [ADR-0003 (LLM design)](../docs/decisions/ADR-0003-model-agnostic-llm.md)
