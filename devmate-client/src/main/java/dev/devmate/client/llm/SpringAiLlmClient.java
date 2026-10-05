package dev.devmate.client.llm;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * {@link LlmClient} over Spring AI's {@link ChatModel}. One adapter serves every provider: whichever
 * {@code ChatModel} Spring AI auto-configures (selected by {@code spring.ai.model.chat}) is injected here.
 */
@Component
public class SpringAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);

    private final ChatModel chatModel;
    private final LlmProperties properties;

    public SpringAiLlmClient(ChatModel chatModel, LlmProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
        log.info("LLM configured provider={} model={}", properties.provider(), properties.model());
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long start = System.nanoTime();
        ChatResponse response;
        try {
            response = chatModel.call(new Prompt(messages(request)));
        } catch (RuntimeException e) {
            log.warn("LLM call failed provider={} model={} error={}", properties.provider(), properties.model(),
                    e.getClass().getSimpleName());
            throw new LlmException("LLM provider '" + properties.provider() + "' failed: " + rootMessage(e), e);
        }
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new LlmException("LLM provider '" + properties.provider() + "' returned no answer", null);
        }

        var metadata = response.getMetadata();
        Usage usage = metadata.getUsage();
        var result = new LlmResponse(
                response.getResult().getOutput().getText(),
                metadata.getModel() == null || metadata.getModel().isBlank() ? properties.model() : metadata.getModel(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
        log.info("LLM call succeeded provider={} model={} inputTokens={} outputTokens={} durationMs={}",
                properties.provider(), result.model(), result.inputTokens(), result.outputTokens(),
                (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    private static List<Message> messages(LlmRequest request) {
        var messages = new ArrayList<Message>(2);
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }
        messages.add(new UserMessage(request.userMessage()));
        return messages;
    }

    /** Provider SDKs wrap the useful cause (connection refused, 401, unknown model) several levels deep. */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
