package dev.devmate.client.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/**
 * {@link LlmClient} over Spring AI's {@link ChatModel}. One adapter serves every provider: whichever
 * {@code ChatModel} Spring AI auto-configures (selected by {@code spring.ai.model.chat}) is injected here.
 *
 * <p>Tool calling: the tool definitions go to the model; DevMate runs the loop itself (model → tool calls → results
 * → model …) so it can cap the rounds and record every call. Spring AI 2.0 chat models never execute tools.
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
        Map<String, LlmTool> tools = new LinkedHashMap<>();
        request.tools().forEach(tool -> tools.put(tool.name(), tool));
        ChatOptions options = tools.isEmpty() ? null
                : withTools(tools.values().stream().map(SpringAiLlmClient::toCallback).toList());

        List<Message> messages = messages(request);
        var calls = new ArrayList<LlmToolCall>();
        var usage = new TokenTotals();
        for (int round = 0; ; round++) {
            ChatResponse response = call(new Prompt(messages, options));
            usage.add(response.getMetadata().getUsage());
            AssistantMessage output = response.getResult().getOutput();

            if (!response.hasToolCalls() || tools.isEmpty()) {
                return finish(output.getText(), response, usage, calls, start);
            }
            if (round == request.maxToolRounds()) {
                log.warn("LLM tool round limit reached rounds={} toolCalls={}", round, calls.size());
                return finish("I stopped after " + round + " rounds of tool calls without reaching an answer. "
                        + "Try a more specific question.", response, usage, calls, start);
            }
            messages.add(output);
            messages.add(runTools(output.getToolCalls(), tools, calls));
        }
    }

    /**
     * The model's configured options (model, temperature, max tokens…) plus the tools. Options passed with a prompt
     * replace the defaults rather than merging with them, so they must start from the defaults.
     */
    private ChatOptions withTools(List<ToolCallback> callbacks) {
        ChatOptions defaults = chatModel.getOptions();
        ToolCallingChatOptions.Builder<?> builder = defaults instanceof ToolCallingChatOptions toolOptions
                ? toolOptions.mutate()
                : ToolCallingChatOptions.builder().model(properties.model());
        return builder.toolCallbacks(callbacks).build();
    }

    private ChatResponse call(Prompt prompt) {
        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException e) {
            log.warn("LLM call failed provider={} model={} error={}", properties.provider(), properties.model(),
                    e.getClass().getSimpleName());
            throw new LlmException("LLM provider '" + properties.provider() + "' failed: " + rootMessage(e), e);
        }
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new LlmException("LLM provider '" + properties.provider() + "' returned no answer", null);
        }
        return response;
    }

    private static ToolResponseMessage runTools(List<AssistantMessage.ToolCall> requested, Map<String, LlmTool> tools,
                                                List<LlmToolCall> calls) {
        var responses = new ArrayList<ToolResponseMessage.ToolResponse>(requested.size());
        for (AssistantMessage.ToolCall call : requested) {
            String arguments = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
            LlmTool tool = tools.get(call.name());
            LlmTool.Output output;
            long durationMs;
            long start = System.nanoTime();
            MDC.put("toolName", call.name());
            try {
                output = tool == null
                        ? LlmTool.Output.error("UNKNOWN_TOOL: no tool named '" + call.name() + "'")
                        : tool.handler().apply(arguments);
                durationMs = (System.nanoTime() - start) / 1_000_000;
                log.info("tool call finished error={} durationMs={}", output.error(), durationMs);
            } finally {
                MDC.remove("toolName");
            }
            calls.add(new LlmToolCall(call.name(), arguments, output.error(), output.summary(), durationMs));
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), output.text()));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private LlmResponse finish(String text, ChatResponse last, TokenTotals usage, List<LlmToolCall> calls,
                               long startNanos) {
        String reported = last.getMetadata().getModel();
        var result = new LlmResponse(text == null ? "" : text,
                reported == null || reported.isBlank() ? properties.model() : reported,
                usage.input, usage.output, calls);
        log.info("LLM call succeeded provider={} model={} inputTokens={} outputTokens={} toolCalls={} durationMs={}",
                properties.provider(), result.model(), result.inputTokens(), result.outputTokens(), calls.size(),
                (System.nanoTime() - startNanos) / 1_000_000);
        return result;
    }

    private static ToolCallback toCallback(LlmTool tool) {
        // Spring AI rejects a blank description; MCP makes it optional.
        String description = tool.description().isBlank() ? tool.name() : tool.description();
        ToolDefinition definition = new DefaultToolDefinition(tool.name(), description, tool.inputSchema());
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                return tool.handler().apply(arguments).text();
            }
        };
    }

    private static List<Message> messages(LlmRequest request) {
        var messages = new ArrayList<Message>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }
        messages.add(new UserMessage(request.userMessage()));
        return messages;
    }

    /** Sums usage over tool rounds; stays null when the provider reports nothing. */
    private static final class TokenTotals {
        Integer input;
        Integer output;

        void add(Usage usage) {
            if (usage == null) {
                return;
            }
            input = sum(input, usage.getPromptTokens());
            output = sum(output, usage.getCompletionTokens());
        }

        private static Integer sum(Integer total, Integer value) {
            return value == null ? total : (total == null ? value : total + value);
        }
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
