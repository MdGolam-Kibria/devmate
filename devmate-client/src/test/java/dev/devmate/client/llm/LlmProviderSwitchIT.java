package dev.devmate.client.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.devmate.client.DevMateClientApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves the application.yml switch: setting {@code devmate.llm.provider/model} (what commenting/uncommenting the
 * blocks does) activates exactly that provider's ChatModel with that model name. No network calls are made.
 */
class LlmProviderSwitchIT {

    @Test
    void anthropicIsTheDefaultProvider() {
        try (var context = start()) {
            assertThat(context.getBeansOfType(ChatModel.class).values())
                    .singleElement().isInstanceOf(AnthropicChatModel.class);
            assertThat(context.getBean(LlmProperties.class))
                    .isEqualTo(new LlmProperties("anthropic", "claude-sonnet-5-5"));
            assertThat(context.getEnvironment().getProperty("spring.ai.anthropic.chat.model"))
                    .isEqualTo("claude-sonnet-5-5");
        }
    }

    @Test
    void ollamaBlockActivatesOllamaOnly() {
        try (var context = start("--devmate.llm.provider=ollama", "--devmate.llm.model=llama3.2:3b")) {
            assertThat(context.getBeansOfType(ChatModel.class).values())
                    .singleElement().isInstanceOf(OllamaChatModel.class);
            assertThat(context.getEnvironment().getProperty("spring.ai.ollama.chat.model")).isEqualTo("llama3.2:3b");
            assertThat(context.getEnvironment().getProperty("spring.ai.ollama.base-url"))
                    .isEqualTo("http://localhost:11434");
        }
    }

    @Test
    void modelCanBeOverriddenByEnvironmentWithoutEditingYaml() {
        try (var context = start("--DEVMATE_LLM_MODEL=claude-opus-5-5")) {
            assertThat(context.getBean(LlmProperties.class).model()).isEqualTo("claude-opus-5-5");
        }
    }

    @Test
    void anthropicWithoutApiKeyFailsFastWithClearMessage() {
        assertThatThrownBy(() -> start("--spring.ai.anthropic.api-key=").close())
                .rootCause()
                .hasMessageContaining("set the ANTHROPIC_API_KEY environment variable");
    }

    @Test
    void ollamaNeedsNoApiKey() {
        try (var context = start("--devmate.llm.provider=ollama", "--spring.ai.anthropic.api-key=")) {
            assertThat(context.getBean(LlmClient.class)).isNotNull();
        }
    }

    private static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(DevMateClientApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
