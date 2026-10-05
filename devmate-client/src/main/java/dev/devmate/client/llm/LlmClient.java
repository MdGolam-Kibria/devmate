package dev.devmate.client.llm;

/**
 * DevMate's port to a language model (ADR-0003). Callers never see provider or Spring AI types, so the model behind
 * it (Anthropic, local Ollama, …) is purely configuration.
 */
public interface LlmClient {

    /**
     * @throws LlmException if the provider is unreachable, rejects the request or returns no answer
     */
    LlmResponse chat(LlmRequest request);
}
