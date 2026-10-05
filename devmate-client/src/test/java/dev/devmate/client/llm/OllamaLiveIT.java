package dev.devmate.client.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.devmate.client.DevMateClientApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Real round trip to a local Ollama. Opt-in so builds never depend on a running model:
 * {@code DEVMATE_LIVE_OLLAMA=true ./mvnw -pl devmate-client verify -Dit.test=OllamaLiveIT}.
 * Model defaults to {@code llama3.2:3b}; override with {@code DEVMATE_LLM_MODEL}.
 */
@EnabledIfEnvironmentVariable(named = "DEVMATE_LIVE_OLLAMA", matches = "true")
class OllamaLiveIT {

    @Test
    void localOllamaAnswers() {
        String model = System.getenv().getOrDefault("DEVMATE_LLM_MODEL", "llama3.2:3b");
        try (var context = new SpringApplicationBuilder(DevMateClientApplication.class)
                .web(WebApplicationType.NONE)
                .run("--devmate.llm.provider=ollama", "--devmate.llm.model=" + model)) {

            LlmResponse response = context.getBean(LlmClient.class)
                    .chat(new LlmRequest("Answer with one word.", "What is the capital of France?"));

            assertThat(response.text()).containsIgnoringCase("Paris");
            assertThat(response.model()).startsWith(model.split(":")[0]);
        }
    }
}
