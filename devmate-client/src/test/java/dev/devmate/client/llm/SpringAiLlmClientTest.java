package dev.devmate.client.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;

class SpringAiLlmClientTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final SpringAiLlmClient client =
            new SpringAiLlmClient(chatModel, new LlmProperties("ollama", "llama3.2:3b"));

    @Test
    void mapsTextModelAndUsage() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("Hello", "llama3.2:3b-q4", 12, 3));

        LlmResponse response = client.chat(LlmRequest.of("Hi"));

        assertThat(response).isEqualTo(new LlmResponse("Hello", "llama3.2:3b-q4", 12, 3));
    }

    @Test
    void sendsSystemPromptBeforeUserMessage() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ok", "m", 1, 1));

        client.chat(new LlmRequest("be brief", "question"));

        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .satisfiesExactly(
                        m -> assertThat(m).isInstanceOf(SystemMessage.class).extracting("text").isEqualTo("be brief"),
                        m -> assertThat(m).isInstanceOf(UserMessage.class).extracting("text").isEqualTo("question"));
    }

    @Test
    void omitsBlankSystemPrompt() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ok", "m", 1, 1));

        client.chat(new LlmRequest("  ", "question"));

        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions()).singleElement().isInstanceOf(UserMessage.class);
    }

    @Test
    void fallsBackToConfiguredModelWhenProviderDoesNotReportOne() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ok", null, null, null));

        assertThat(client.chat(LlmRequest.of("x")).model()).isEqualTo("llama3.2:3b");
    }

    @Test
    void providerFailureBecomesLlmExceptionWithRootCause() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("wrapper", new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.chat(LlmRequest.of("x")))
                .isInstanceOf(LlmException.class)
                .hasMessage("LLM provider 'ollama' failed: Connection refused");
    }

    @Test
    void emptyResponseBecomesLlmException() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));

        assertThatThrownBy(() -> client.chat(LlmRequest.of("x")))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("returned no answer");
    }

    // ── tool calling ─────────────────────────────────────────────────────────────────────────

    private final List<String> toolArguments = new ArrayList<>();
    private final LlmTool readFile = new LlmTool("read_file", "Read a file", "{\"type\":\"object\"}", arguments -> {
        toolArguments.add(arguments);
        return LlmTool.Output.ok("file content");
    });

    @Test
    void runsRequestedToolsAndSendsResultsBackUntilTheModelAnswers() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCall("read_file", "{\"path\":\"pom.xml\"}", 10, 2))
                .thenReturn(response("It is a Maven build.", "m", 30, 5));

        LlmResponse answer = client.chat(new LlmRequest("sys", "What is pom.xml?", List.of(readFile), 3));

        assertThat(answer.text()).isEqualTo("It is a Maven build.");
        assertThat(answer.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("read_file");
            assertThat(call.arguments()).isEqualTo("{\"path\":\"pom.xml\"}");
            assertThat(call.error()).isFalse();
            assertThat(call.summary()).isEqualTo("12 chars");
            assertThat(call.durationMs()).isNotNegative();
        });
        assertThat(answer.inputTokens()).isEqualTo(40);
        assertThat(answer.outputTokens()).isEqualTo(7);
        assertThat(toolArguments).containsExactly("{\"path\":\"pom.xml\"}");

        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        Prompt second = prompts.getAllValues().get(1);
        assertThat(second.getInstructions()).hasSize(4);
        assertThat(second.getInstructions().get(2)).isInstanceOfSatisfying(AssistantMessage.class,
                m -> assertThat(m.hasToolCalls()).isTrue());
        assertThat(second.getInstructions().get(3)).isInstanceOfSatisfying(ToolResponseMessage.class,
                m -> assertThat(m.getResponses()).singleElement()
                        .satisfies(r -> assertThat(r.responseData()).isEqualTo("file content")));
        assertThat(second.getOptions()).isInstanceOfSatisfying(ToolCallingChatOptions.class,
                o -> assertThat(o.getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                        .containsExactly("read_file"));
    }

    @Test
    void toolOptionsKeepTheModelsConfiguredDefaults() {
        when(chatModel.getOptions()).thenReturn(OllamaChatOptions.builder().model("llama3.2:3b").temperature(0.1).build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ok", "m", 1, 1));

        client.chat(new LlmRequest(null, "q", List.of(readFile), 3));

        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions().getModel()).isEqualTo("llama3.2:3b");
        assertThat(prompt.getValue().getOptions().getTemperature()).isEqualTo(0.1);
    }

    @Test
    void unknownToolIsReportedToTheModelAsAnError() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCall("rm_rf", "{}", 1, 1))
                .thenReturn(response("sorry", "m", 1, 1));

        LlmResponse answer = client.chat(new LlmRequest(null, "q", List.of(readFile), 3));

        assertThat(answer.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.error()).isTrue();
            assertThat(call.summary()).isEqualTo("UNKNOWN_TOOL: no tool named 'rm_rf'");
        });
        assertThat(toolArguments).isEmpty();
    }

    @Test
    void stopsAfterTheRoundLimit() {
        when(chatModel.call(any(Prompt.class))).thenReturn(toolCall("read_file", "{}", 1, 1));

        LlmResponse answer = client.chat(new LlmRequest(null, "q", List.of(readFile), 2));

        assertThat(answer.text()).startsWith("I stopped after 2 rounds of tool calls");
        assertThat(answer.toolCalls()).hasSize(2);
        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void toolFailureAbortsTheAnswerUnwrapped() {
        var broken = new LlmTool("read_file", "", "{}", arguments -> {
            throw new IllegalStateException("server down");
        });
        when(chatModel.call(any(Prompt.class))).thenReturn(toolCall("read_file", "{}", 1, 1));

        assertThatThrownBy(() -> client.chat(new LlmRequest(null, "q", List.of(broken), 2)))
                .isInstanceOf(IllegalStateException.class).hasMessage("server down");
    }

    @Test
    void toolWithoutDescriptionIsDescribedByItsName() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ok", "m", 1, 1));

        client.chat(new LlmRequest(null, "q", List.of(new LlmTool("ping", null, "{}", a -> LlmTool.Output.ok(""))), 1));

        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(((ToolCallingChatOptions) prompt.getValue().getOptions()).getToolCallbacks())
                .singleElement().satisfies(c -> assertThat(c.getToolDefinition().description()).isEqualTo("ping"));
    }

    @Test
    void requestWithoutToolsSendsNoToolOptions() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ok", "m", 1, 1));

        client.chat(LlmRequest.of("q"));

        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions()).isNull();
    }

    private static ChatResponse toolCall(String name, String arguments, int in, int out) {
        var message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments))).build();
        var metadata = ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(in, out)).build();
        return new ChatResponse(List.of(new Generation(message)), metadata);
    }

    private static ChatResponse response(String text, String model, Integer in, Integer out) {
        var metadata = ChatResponseMetadata.builder()
                .model(model == null ? "" : model)
                .usage(new DefaultUsage(in, out))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }
}
