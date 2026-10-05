# ADR-0003 — Model-agnostic LLM layer (any provider, including local Ollama)

- Status: Accepted. **Partly implemented early (2026-10-05, on request):** provider switch + plain chat
  (`LlmClient`, `SpringAiLlmClient`, `POST /api/chat`) for `anthropic` (active) and `ollama` (commented block).
  Tool calling, the agent and the Ollama tool-capability check remain in **M3**.
- Date: 2026-10-05

## Context

DevMate must work with **any LLM**: hosted models (Anthropic Claude, OpenAI, Google, Mistral, DeepSeek, Bedrock,
Azure OpenAI) and **local models served by Ollama**, so code and data can stay on the machine. The model
choice must be a configuration change, never a code change.

Environment checked on 2026-10-05:
- Ollama runs locally (`localhost:11434`) with `llama3.2:3b` (chat, tools), `qwen2.5-coder:1.5b` (code chat) and
  `nomic-embed-text` (embeddings).
- Spring AI 2.0.1, already in the build and built on Boot 4.1.1, ships chat starters for `ollama`, `openai`,
  `anthropic`, `google-genai`, `mistral-ai`, `deepseek`, `bedrock-converse`, and an Ollama embedding model.

## Decision

1. **DevMate owns the port.** The agent depends only on a DevMate `LlmClient` interface with DevMate types
   (`LlmRequest`, messages, `ToolDefinition` built from the M2 `ToolRegistry`, `LlmResponse` with tool calls,
   token usage). No provider or Spring AI type appears in agent code.
2. **One adapter covers every provider.** `SpringAiLlmClient` implements `LlmClient` on top of Spring AI's
   `ChatModel`. Each provider is just a Spring AI starter, so adding a provider means adding a dependency, not code.
3. **The active provider and model are pure configuration:**

   ```yaml
   devmate:
     llm:
       provider: anthropic         # anthropic | ollama today; add a Spring AI starter for others
       model: claude-sonnet-5-5    # any model name the provider accepts
   ```

   `provider` maps to `spring.ai.model.chat`, so only that provider's auto-configuration activates and the others
   need no credentials. Provider endpoints and keys come from environment variables
   (`OLLAMA_BASE_URL`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `OPENAI_BASE_URL`, …) and never from source.
4. **Switching is a comment swap.** `application.yml` has an Anthropic block (active, by the owner's choice) and an
   Ollama block (commented). Swapping the comments switches provider. Selecting a hosted provider without its API key
   fails startup with a clear message (`LlmCredentialsCheck`); Ollama needs no key, so no data leaves the machine.
5. **OpenAI-compatible servers** (LM Studio, vLLM, llama.cpp server, LocalAI, OpenRouter, Groq, Azure OpenAI)
   work through `provider: openai` with `OPENAI_BASE_URL`, which covers almost any other endpoint.
6. **Capability check at startup.** DevMate needs tool calling. For Ollama, the configured model's capabilities
   are read from `/api/show`. A model without `tools` fails startup with a clear message instead of silently
   ignoring tools. Hosted providers are checked with a one-time probe call and a readiness health indicator.
7. **Embeddings follow the same pattern (M6).** A DevMate `EmbeddingClient` port on Spring AI `EmbeddingModel`.
   The local default is `nomic-embed-text` on Ollama.

## Alternatives

- **Code directly against one provider SDK** (e.g. Anthropic). Simplest, but it breaks the "any model" requirement
  and leaks provider types into the agent.
- **Use Spring AI `ChatClient` throughout the agent.** It is provider-agnostic, but it still couples the agent to
  Spring AI's API and makes the agent loop harder to unit-test without the framework. Our own port keeps the agent
  plain and lets us swap the adapter (e.g. raw HTTP, LangChain4j) if Spring AI ever lags behind.
- **A generic OpenAI-compatible client only.** It covers Ollama (`/v1`) and many servers, but loses
  provider-specific features (Anthropic prompt caching, Ollama model capability metadata, native tool formats).
  It is kept as a fallback through decision 5.

## Consequences

- Answer quality depends on the model. Small local models (1.5B–3B, like the ones installed now) are good for
  wiring and privacy, but pick tools less reliably. A 7B+ tool-capable local model or a hosted model is recommended
  for real investigations (M4+). This will be documented, not hidden.
- The agent must handle models that return malformed tool calls, unknown tool names or no tool call at all. M3
  needs tests for this using a fake `LlmClient`, with no model required.
- M3 ITs run against a stub/fake model by default. An optional, tagged IT runs against a real local Ollama when
  available. CI never needs an API key.
- Token usage and latency are recorded per call for every provider, where the provider reports them.
