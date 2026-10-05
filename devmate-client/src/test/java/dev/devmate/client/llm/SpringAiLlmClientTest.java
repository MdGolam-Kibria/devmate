package dev.devmate.client.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

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

    private static ChatResponse response(String text, String model, Integer in, Integer out) {
        var metadata = ChatResponseMetadata.builder()
                .model(model == null ? "" : model)
                .usage(new DefaultUsage(in, out))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }
}
