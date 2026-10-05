package dev.devmate.client.llm;

import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup when the selected hosted provider has no API key, instead of starting and returning 401 on the
 * first question. Local providers (ollama) need no key.
 */
@Component
class LlmCredentialsCheck {

    /** provider → property holding its key, and the env var users set. */
    private static final Map<String, String[]> REQUIRED_KEYS = Map.of(
            "anthropic", new String[] {"spring.ai.anthropic.api-key", "ANTHROPIC_API_KEY"},
            "openai", new String[] {"spring.ai.openai.api-key", "OPENAI_API_KEY"});

    LlmCredentialsCheck(LlmProperties properties, Environment environment) {
        String[] required = REQUIRED_KEYS.get(properties.provider());
        if (required != null && environment.getProperty(required[0], "").isBlank()) {
            throw new IllegalStateException("devmate.llm.provider=" + properties.provider()
                    + " needs an API key: set the " + required[1]
                    + " environment variable, or switch to the ollama block in application.yml");
        }
    }
}
