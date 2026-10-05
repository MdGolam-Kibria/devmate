package dev.devmate.client.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MarkdownRendererTest {

    private final MarkdownRenderer markdown = new MarkdownRenderer();

    @Test
    void rendersCommonFormatting() {
        String html = markdown.toSafeHtml("""
                Three causes:

                1. **Network** latency
                2. `max.poll.records` too high

                ```java
                consumer.poll(Duration.ofMillis(100));
                ```
                """);

        assertThat(html).contains("<strong>Network</strong>", "<ol>", "<code>max.poll.records</code>",
                "<pre><code class=\"language-java\">consumer.poll(Duration.ofMillis(100));");
    }

    @Test
    void rendersTables() {
        assertThat(markdown.toSafeHtml("| a | b |\n|---|---|\n| 1 | 2 |")).contains("<table>", "<td>1</td>");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "<a href=\"javascript:alert(1)\">x</a>",
            "<iframe src=\"https://evil.example\"></iframe>"})
    void escapesRawHtml(String input) {
        String html = markdown.toSafeHtml(input);

        assertThat(html).doesNotContain("<script", "<img", "<a ", "<iframe").contains("&lt;");
    }

    @Test
    void neutralisesJavascriptLinks() {
        String html = markdown.toSafeHtml("[click](javascript:alert(1)) and ![x](javascript:alert(2))");

        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    void keepsSafeLinks() {
        assertThat(markdown.toSafeHtml("[docs](https://kafka.apache.org)"))
                .contains("<a rel=\"nofollow\" href=\"https://kafka.apache.org\">docs</a>");
    }

    @Test
    void nullIsEmpty() {
        assertThat(markdown.toSafeHtml(null)).isEmpty();
    }
}
