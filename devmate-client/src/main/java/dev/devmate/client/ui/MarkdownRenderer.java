package dev.devmate.client.ui;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/**
 * Turns model output (Markdown) into HTML that is safe to insert unescaped: raw HTML in the input is escaped, not
 * passed through, and link/image URLs with unsafe schemes ({@code javascript:} etc.) are neutralised.
 * Parser and renderer are immutable and thread-safe.
 */
@Component
public class MarkdownRenderer {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());

    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .softbreak("<br>")
            .build();

    public String toSafeHtml(String markdown) {
        return markdown == null ? "" : renderer.render(parser.parse(markdown));
    }
}
