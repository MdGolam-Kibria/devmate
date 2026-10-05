package dev.devmate.client.llm;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The single switch for which model DevMate talks to. {@code application.yml} feeds these values into
 * {@code spring.ai.model.chat} and the provider's {@code chat.model}, so only the selected provider is activated.
 *
 * @param provider Spring AI chat provider id: {@code anthropic}, {@code ollama}, …
 * @param model    model name as that provider knows it, e.g. {@code claude-sonnet-5-5} or {@code llama3.2:3b}
 */
@Validated
@ConfigurationProperties("devmate.llm")
public record LlmProperties(@NotBlank String provider, @NotBlank String model) {}
