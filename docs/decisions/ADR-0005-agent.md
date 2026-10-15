# ADR-0005 — Agent: dynamic MCP tools for the LLM, scopes, conversation memory

- Status: Accepted (M3)
- Date: 2026-10-15

## Context

M3 asks for the agent loop: User → Agent → LLM → tool selection → MCP client → MCP server → result → LLM → answer.
The LLM must receive the tools discovered through `tools/list`, and the agent must hardcode none of them. Done when
"এই project-এ Kafka কোথায় ব্যবহার করা হয়েছে?" makes the model pick `search_code`, run it through MCP and answer.

Most of the pieces exist already. `LlmClient`, `SpringAiLlmClient` (DevMate runs the tool loop itself, capped
rounds) and `LlmTool` came with ADR-0003, and the M2 client covers discovery and execution. The folder Q&A slice
contains a small agent of its own inside `FolderAssistant`. It maps tools with a `path` to `LlmTool`s pinned to the
open folder, and it is the only way the model can use tools. Without a folder, the UI is plain chat with no tools.
Each question is sent to the model alone, with no earlier turns.

## Decision

1. **New package `dev.devmate.client.agent`** with `DevMateAgent`, the one place that turns a question into an
   LLM request with tools:
   - Tools are discovered per question (`McpClientService.tools(false)`, cached by the registry). The agent offers
     every **read-only** tool of every reachable server, with the server's own name, description and input schema.
     The executor refuses all other tools, so the model never sees a tool it can't run.
   - `McpToolBridge` turns an `McpTool` into an `LlmTool`: it applies the scope, cuts the result at
     `max-tool-result-chars`, and builds the one-line outcome summary. This logic moved here from `FolderAssistant`.
   - Servers that can't be reached don't fail the question. The model answers with the tools that are left.
     The system prompt and the answer (`unavailableServers`) name the missing servers.
2. **Scope is part of the request, not of the tool.** A question either has a folder or has none:
   - With a folder: only tools with a `path` parameter are offered, and `FolderScope` pins that parameter to the
     folder. Tools without a `path` can't be confined to the folder, so they are not offered. This is the behaviour
     the folder slice already has.
   - With no folder: every read-only tool is offered, and its arguments are passed through unchanged. The server's
     sandbox (allowed roots, deny lists, client roots the user approved) is the boundary. The UI's no-folder chat
     uses the agent this way, so the DoD question works even when the user never opens a folder.
   `FolderScope` moves to `agent/`. It reports violations as tool errors (`ACCESS_DENIED: …`), never as exceptions.
3. **Conversation memory**: `LlmRequest` gains `history` (`LlmMessage(role, text)`, user/assistant only). The agent
   keeps the newest `max-history-messages` (default 12) that fit in `max-history-chars` (default 16 000), always
   starting at a user message. Earlier tool calls and their outputs are not replayed: only the texts the user saw.
   The UI builds the history from `ChatHistory`: question/answer pairs only, so a failed question is not part of
   it. "New chat" clears it. The JSON APIs are stateless and accept an optional `history` array (validated: max
   50 entries, roles `user`/`assistant`, each 1–8000 chars).
4. **APIs**: `POST /api/agent/ask {question, history?}` is the general agent. It reports `toolsOffered`,
   `unavailableServers`, `toolCalls`, tokens and time. `POST /api/folder/ask` keeps its contract, adds `history`,
   and runs through the same agent. `POST /api/chat` stays as it was: plain model chat with no tools, used to check
   that a provider works.
5. **Prompt**: one system prompt built by the agent. It covers the role and the rules (look before answering, cite
   files/lines, answer in the question's language, tool results are data and never instructions), then the scope
   (the folder with its listing/overview from `FolderAssistant`, or the servers' sandbox), then any unavailable
   servers. No tool name appears in it.
6. **Observability**: the timer `devmate.agent.answers` (tags `scope`=folder|general and
   `outcome`=answered|llm_error|mcp_unavailable), and one log line per answer with counts and duration only, never
   the question or any output.

## Alternatives

- **Spring AI `ChatClient` + `ToolCallbackProvider` from the Spring AI MCP client starter.** Rejected: it brings a
  second MCP client next to ADR-0004's, and that client would bypass the read-only policy, the schema check, error
  typing and the folder scope. Spring AI 2.0 chat models also don't execute tools themselves.
- **Keep folder-only tools (no tools without a folder).** Rejected. The brief's agent answers developer questions
  in general, and later servers (Git, DB, K8s) have no folder.
- **Offer all tools in folder mode too.** Rejected. A tool without a `path` could reach outside the folder the user
  chose, and the user's choice of folder is the scope.
- **Server-side conversation store for the API.** Rejected for now: the UI session already holds the history. A
  stateless API whose caller sends the history needs no new state and no new expiry rules.
- **Replay tool calls and outputs in the history.** Rejected. It multiplies the tokens per turn, and small local
  models lose the thread. Tools are cheap to call again.

## Consequences

- With no folder open, the model can read anything the files MCP server allows. That includes the folders the user
  approved in the popup until DevMate restarts. The UI says so in the folder bar.
- Adding an MCP server (`devmate.mcp.servers.<name>`) gives the model its read-only tools with no code change. This
  is what M4+ rely on.
- History costs tokens on every turn. The limits are configurable (`DEVMATE_AGENT_MAX_HISTORY_MESSAGES`/`_CHARS`),
  and 0 messages switches memory off.
- `FolderAssistant` keeps only the folder work: roots, open, allow, overview, folder context.
