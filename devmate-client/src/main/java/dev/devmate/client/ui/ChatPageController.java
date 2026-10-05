package dev.devmate.client.ui;

import dev.devmate.client.llm.LlmClient;
import dev.devmate.client.llm.LlmException;
import dev.devmate.client.llm.LlmProperties;
import dev.devmate.client.llm.LlmRequest;
import dev.devmate.client.llm.LlmResponse;
import dev.devmate.client.ui.ChatHistory.Role;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-rendered chat page (Thymeleaf). Works without JavaScript; every POST redirects back to {@code /}
 * (post/redirect/get) so a browser refresh never re-sends a question.
 */
@Controller
public class ChatPageController {

    private final LlmClient llm;
    private final LlmProperties properties;
    private final ChatHistory history;
    private final MarkdownRenderer markdown;

    public ChatPageController(LlmClient llm, LlmProperties properties, ChatHistory history,
                              MarkdownRenderer markdown) {
        this.llm = llm;
        this.properties = properties;
        this.history = history;
        this.markdown = markdown;
    }

    @GetMapping("/")
    public String page(Model model) {
        model.addAttribute("provider", properties.provider());
        model.addAttribute("model", properties.model());
        model.addAttribute("entries", history.entries());
        model.addAttribute("maxLength", LlmRequest.MAX_USER_MESSAGE_LENGTH);
        return "chat";
    }

    @PostMapping("/chat")
    public String send(@RequestParam(defaultValue = "") String message, RedirectAttributes redirect) {
        String question = message.strip();
        if (question.isEmpty() || question.length() > LlmRequest.MAX_USER_MESSAGE_LENGTH) {
            redirect.addFlashAttribute("formError",
                    "Message must be 1–" + LlmRequest.MAX_USER_MESSAGE_LENGTH + " characters.");
            redirect.addFlashAttribute("draft", message);
            return "redirect:/";
        }

        history.add(Role.USER, question, null, null);
        long start = System.nanoTime();
        try {
            LlmResponse answer = llm.chat(LlmRequest.of(question));
            history.add(Role.ASSISTANT, answer.text(), markdown.toSafeHtml(answer.text()), details(answer, start));
        } catch (LlmException e) {
            history.add(Role.ERROR, e.getMessage(), null, null);
        }
        return "redirect:/";
    }

    @PostMapping("/chat/clear")
    public String clear() {
        history.clear();
        return "redirect:/";
    }

    private static String details(LlmResponse answer, long startNanos) {
        var details = new StringBuilder(answer.model());
        if (answer.inputTokens() != null && answer.outputTokens() != null) {
            details.append(" · ").append(answer.inputTokens()).append(" in / ")
                    .append(answer.outputTokens()).append(" out tokens");
        }
        return details.append(" · ").append((System.nanoTime() - startNanos) / 1_000_000).append(" ms").toString();
    }
}
