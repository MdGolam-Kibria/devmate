package dev.devmate.client.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.devmate.client.llm.LlmClient;
import dev.devmate.client.llm.LlmException;
import dev.devmate.client.llm.LlmRequest;
import dev.devmate.client.llm.LlmResponse;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The Thymeleaf chat page over real HTTP, as a browser would use it (session cookie, form posts, redirects). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"devmate.llm.provider=anthropic", "devmate.llm.model=claude-sonnet-5-5"})
class ChatPageIT {

    @MockitoBean
    LlmClient llm;

    @Value("${local.server.port}")
    int port;

    private HttpClient browser;

    @BeforeEach
    void newBrowserSession() {
        browser = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Test
    void emptyPageShowsProviderModelAndComposer() throws Exception {
        var page = get("/");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/html"));
        assertThat(page.body())
                .contains("Ask DevMate", ">anthropic<", ">claude-sonnet-5-5<", "name=\"message\"",
                        "maxlength=\"" + LlmRequest.MAX_USER_MESSAGE_LENGTH + "\"", "Messages are sent to anthropic.");
    }

    @Test
    void sendingAMessageShowsQuestionAnswerAndDetails() throws Exception {
        when(llm.chat(any())).thenReturn(new LlmResponse("Kafka is a log.", "claude-sonnet-5-5", 11, 4));

        var page = post("/chat", "message=" + enc("  What is Kafka?  "));

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.uri().getPath()).isEqualTo("/");
        assertThat(page.body())
                .contains(">What is Kafka?<", ">Kafka is a log.<", "claude-sonnet-5-5 · 11 in / 4 out tokens")
                .doesNotContain("Ask DevMate");
        verify(llm).chat(LlmRequest.of("What is Kafka?"));
    }

    @Test
    void historyIsPerSessionAndClearable() throws Exception {
        when(llm.chat(any())).thenReturn(new LlmResponse("answer", "m", null, null));
        post("/chat", "message=first");

        HttpClient otherBrowser = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        assertThat(otherBrowser.send(request("/").GET().build(), HttpResponse.BodyHandlers.ofString()).body())
                .doesNotContain(">first<");

        assertThat(post("/chat/clear", "").body()).contains("Ask DevMate").doesNotContain(">first<");
    }

    @Test
    void modelOutputIsHtmlEscaped() throws Exception {
        when(llm.chat(any())).thenReturn(new LlmResponse("<script>alert(1)</script>", "m", null, null));

        var body = post("/chat", "message=" + enc("<b>hi</b>")).body();

        assertThat(body).contains("&lt;script&gt;alert(1)&lt;/script&gt;", "&lt;b&gt;hi&lt;/b&gt;")
                .doesNotContain("<script>alert(1)");
    }

    @Test
    void assistantMarkdownIsRenderedButUserTextIsNot() throws Exception {
        when(llm.chat(any())).thenReturn(new LlmResponse("1. **Lag** from `poll`", "m", null, null));

        var body = post("/chat", "message=" + enc("**not bold**")).body();

        assertThat(body).contains("<strong>Lag</strong>", "<code>poll</code>", "<ol>", ">**not bold**<");
    }

    @Test
    void providerFailureIsShownAsErrorMessage() throws Exception {
        when(llm.chat(any())).thenThrow(new LlmException("LLM provider 'anthropic' failed: 401", null));

        var body = post("/chat", "message=hi").body();

        assertThat(body).contains("message error", "LLM provider &#39;anthropic&#39; failed: 401");
    }

    @Test
    void blankOrTooLongMessageIsRejectedWithoutCallingTheModel() throws Exception {
        assertThat(post("/chat", "message=" + enc("   ")).body()).contains("Message must be 1–8000 characters.");
        assertThat(post("/chat", "message=" + "x".repeat(8001)).body()).contains("Message must be 1–8000 characters.");
        verify(llm, never()).chat(any());
    }

    @Test
    void sessionCookieIsStrictAndNeverInUrls() throws Exception {
        var response = HttpClient.newHttpClient().send(request("/chat")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("message=+")).build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).doesNotContain("jsessionid"));
        assertThat(response.headers().firstValue("Set-Cookie"))
                .hasValueSatisfying(cookie -> assertThat(cookie).contains("HttpOnly", "SameSite=Strict"));
    }

    @Test
    void stylesheetIsServed() throws Exception {
        assertThat(get("/css/devmate.css").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return browser.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return browser.send(request(path)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
