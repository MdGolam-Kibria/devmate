package dev.devmate.client.ui;

import dev.devmate.client.folder.FolderAssistant;
import dev.devmate.client.folder.FolderException;
import dev.devmate.client.folder.FolderSnapshot;
import dev.devmate.client.llm.LlmClient;
import dev.devmate.client.llm.LlmException;
import dev.devmate.client.llm.LlmProperties;
import dev.devmate.client.llm.LlmRequest;
import dev.devmate.client.llm.LlmResponse;
import dev.devmate.client.llm.LlmToolCall;
import dev.devmate.client.mcp.McpUnavailableException;
import dev.devmate.client.ui.ChatHistory.Role;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-rendered chat page (Thymeleaf). Works without JavaScript; every POST redirects back to {@code /}
 * (post/redirect/get) so a browser refresh never re-sends a question.
 *
 * <p>Without a folder it is plain chat. With a folder open, questions go to {@link FolderAssistant}, which lets the
 * model read and search that folder through the files MCP server.
 */
@Controller
public class ChatPageController {

    private static final int MAX_PATH_LENGTH = 4096;

    private final LlmClient llm;
    private final LlmProperties properties;
    private final ChatHistory history;
    private final MarkdownRenderer markdown;
    private final SelectedFolder folder;
    private final FolderAssistant folders;
    private final FolderBrowser browser;

    public ChatPageController(LlmClient llm, LlmProperties properties, ChatHistory history, MarkdownRenderer markdown,
                              SelectedFolder folder, FolderAssistant folders, FolderBrowser browser) {
        this.llm = llm;
        this.properties = properties;
        this.history = history;
        this.markdown = markdown;
        this.folder = folder;
        this.folders = folders;
        this.browser = browser;
    }

    @GetMapping("/")
    public String page(Model model) {
        model.addAttribute("provider", properties.provider());
        model.addAttribute("model", properties.model());
        model.addAttribute("entries", history.entries());
        model.addAttribute("maxLength", LlmRequest.MAX_USER_MESSAGE_LENGTH);
        model.addAttribute("folder", folder.path());
        model.addAttribute("folderEntryCount", folder.entryCount());
        return "chat";
    }

    /**
     * The folder picker: browse this machine's folders and click one ({@link FolderBrowser}), or use a root the files
     * MCP server suggests. Starts at {@code browse}, else the path that just failed to open, else the open folder,
     * else the home directory. Roots are fetched on demand so the page never waits for the MCP server.
     */
    @GetMapping("/folder")
    public String picker(@RequestParam(required = false) String browse, Model model) {
        model.addAttribute("browser", browse(browse, model));
        try {
            model.addAttribute("roots", folders.roots());
        } catch (McpUnavailableException e) {
            model.addAttribute("roots", List.of());
            model.addAttribute("mcpError", e.getMessage());
        }
        page(model);
        model.addAttribute("picking", true);
        return "chat";
    }

    @PostMapping("/folder")
    public String open(@RequestParam(defaultValue = "") String path, RedirectAttributes redirect) {
        if (path.length() > MAX_PATH_LENGTH) {
            redirect.addFlashAttribute("folderError", "INVALID_ARGUMENT: path is too long");
            return "redirect:/folder";
        }
        try {
            FolderSnapshot opened = folders.open(path);
            folder.select(opened.path(), opened.entries().size());
            return "redirect:/";
        } catch (FolderException e) {
            if (FolderAssistant.isOutsideAllowedRoots(e)) {
                redirect.addFlashAttribute("approvePath", path.strip());  // ask the user: the popup
            }
            return pickerWithError(e, path, redirect);
        } catch (McpUnavailableException e) {
            return pickerWithError(e, path, redirect);
        }
    }

    /**
     * The user clicked "Allow" in the popup for a folder outside the server's allowed roots. Only this form (a
     * same-site POST from the page, with the user's session) can approve a folder; the JSON API can't.
     */
    @PostMapping("/folder/allow")
    public String allow(@RequestParam(defaultValue = "") String path, RedirectAttributes redirect) {
        if (path.length() > MAX_PATH_LENGTH) {
            redirect.addFlashAttribute("folderError", "INVALID_ARGUMENT: path is too long");
            return "redirect:/folder";
        }
        try {
            FolderSnapshot opened = folders.allow(path);
            folder.select(opened.path(), opened.entries().size());
            return "redirect:/";
        } catch (FolderException | McpUnavailableException e) {
            return pickerWithError(e, path, redirect);
        }
    }

    /** The listing to show, falling back to the home directory; null (picker shows only the error) if both fail. */
    private FolderBrowser.Listing browse(String requested, Model model) {
        if (requested != null && requested.length() > MAX_PATH_LENGTH) {
            model.addAttribute("browseError", "Path is too long");
            requested = null;
        }
        String start = requested != null ? requested
                : model.getAttribute("folderDraft") instanceof String draft && !draft.isBlank() ? draft
                : folder.path();
        try {
            return browser.list(start);
        } catch (FolderBrowser.BrowseException e) {
            if (requested != null) {
                model.addAttribute("browseError", e.getMessage());  // a draft that failed already shows its error
            }
        }
        try {
            return browser.list(null);
        } catch (FolderBrowser.BrowseException e) {
            model.addAttribute("browseError", e.getMessage());
            return null;
        }
    }

    private static String pickerWithError(RuntimeException e, String path, RedirectAttributes redirect) {
        redirect.addFlashAttribute("folderError", e.getMessage());
        redirect.addFlashAttribute("folderDraft", path);
        return "redirect:/folder";
    }

    @PostMapping("/folder/close")
    public String closeFolder() {
        folder.clear();
        return "redirect:/";
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
        String path = folder.path();
        long start = System.nanoTime();
        try {
            LlmResponse answer = path == null ? llm.chat(LlmRequest.of(question)) : folders.ask(path, question);
            history.add(Role.ASSISTANT, answer.text(), markdown.toSafeHtml(answer.text()), details(answer, start),
                    answer.toolCalls().stream().map(ChatPageController::describe).toList());
        } catch (LlmException | McpUnavailableException | FolderException e) {
            history.add(Role.ERROR, e.getMessage(), null, null);
        }
        return "redirect:/";
    }

    @PostMapping("/chat/clear")
    public String clear() {
        history.clear();
        return "redirect:/";
    }

    private static ChatHistory.ToolUse describe(LlmToolCall call) {
        return new ChatHistory.ToolUse(call.name() + " " + call.arguments(),
                (call.error() ? "error: " : "") + call.summary(), call.error(), call.durationMs());
    }

    private static String details(LlmResponse answer, long startNanos) {
        var details = new StringBuilder(answer.model());
        if (answer.inputTokens() != null && answer.outputTokens() != null) {
            details.append(" · ").append(answer.inputTokens()).append(" in / ")
                    .append(answer.outputTokens()).append(" out tokens");
        }
        if (!answer.toolCalls().isEmpty()) {
            details.append(" · ").append(answer.toolCalls().size())
                    .append(answer.toolCalls().size() == 1 ? " tool call" : " tool calls");
        }
        return details.append(" · ").append((System.nanoTime() - startNanos) / 1_000_000).append(" ms").toString();
    }
}
