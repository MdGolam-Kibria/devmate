package dev.devmate.client.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DevMate's connection to one configured MCP server. Opens a {@link McpSession} on first use (connect +
 * {@code initialize}), keeps it while it works, and drops it when the session or transport breaks, so DevMate and the
 * server can start and stop independently. Every failure is classified into an {@link McpErrorCode} and thrown as a
 * {@link McpUnavailableException} whose message is safe to show; JSON-RPC error answers pass through as
 * {@link McpError} (the server works, it refused the request).
 *
 * <p>Thread-safe. Opening a session is serialised; requests on an open session run concurrently.
 */
public final class McpConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpConnection.class);
    private static final int MAX_DETAIL_LENGTH = 200;

    /** A failure as reported in the server status. */
    public record Failure(McpErrorCode code, String message, Instant at) {}

    private final String name;
    private final McpClientProperties.Server properties;
    private final McpTransport transport;
    private final McpRoots roots;
    private final McpSchema.Implementation clientInfo;
    private final AtomicLong toolsVersion = new AtomicLong();
    private volatile McpSession session;       // written under this
    private volatile Failure lastFailure;

    McpConnection(String name, McpClientProperties.Server properties, McpTransport transport, McpRoots roots,
                  McpSchema.Implementation clientInfo) {
        this.name = name;
        this.properties = properties;
        this.transport = transport;
        this.roots = roots;
        this.clientInfo = clientInfo;
    }

    public String name() {
        return name;
    }

    public URI url() {
        return properties.url();
    }

    /** "Files MCP server" for {@code files}: how messages refer to this server. */
    public String displayName() {
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1) + " MCP server";
    }

    /** The open session, or null. */
    public McpSession session() {
        return session;
    }

    /** The most recent failure, or null once a request succeeds again. */
    public Failure lastFailure() {
        return lastFailure;
    }

    /**
     * Changes whenever the server's tools may have changed: a new session was opened, or the server sent
     * {@code notifications/tools/list_changed}. {@link ToolRegistry} re-lists the tools when it changes.
     */
    public long toolsVersion() {
        return toolsVersion.get();
    }

    /**
     * Runs a request on the session, opening one first if needed. When the request fails because the connection,
     * session or transport broke, the session is dropped and, if {@code retryable}, the request is sent once more on a
     * new session; only pass {@code true} for requests that are safe to repeat. Timeouts are never retried.
     *
     * @throws McpUnavailableException if the server can't be used
     * @throws McpError                if the server answered with a JSON-RPC error
     */
    public <T> T call(String operation, Function<McpSession, T> request, boolean retryable) {
        for (int attempt = 1; ; attempt++) {
            McpSession current = open();
            try {
                T result = request.apply(current);
                lastFailure = null;
                return result;
            } catch (RuntimeException e) {
                McpError answer = cause(e, McpError.class);
                if (answer != null) {
                    lastFailure = null;   // the server answered: the session works
                    throw answer;
                }
                McpErrorCode code = classify(e);
                if (code != McpErrorCode.TIMEOUT) {
                    drop(current);
                }
                log.warn("MCP {} failed server={} attempt={} code={} error={}", operation, name, attempt, code,
                        rootCause(e).getClass().getSimpleName());
                McpUnavailableException failure = unavailable(code, operation, e);
                // Timeouts and oversized answers would only repeat themselves.
                if (code == McpErrorCode.TIMEOUT || tooLarge(e) || !retryable || attempt >= 2) {
                    throw failure;
                }
            }
        }
    }

    /** Declares an approved folder on the open session (new sessions get all roots at {@code initialize}). */
    void addRoot(McpSchema.Root root) {
        McpSession current = session;
        if (current != null) {
            try {
                current.addRoot(root);
            } catch (RuntimeException e) {
                drop(current);   // the next session declares the root at initialize
            }
        }
    }

    void removeRoot(String uri) {
        McpSession current = session;
        if (current != null) {
            try {
                current.removeRoot(uri);
            } catch (RuntimeException e) {
                drop(current);
            }
        }
    }

    private synchronized McpSession open() {
        if (session != null) {
            return session;
        }
        McpSyncClient client = McpClient.sync(transport.create(properties))
                .clientInfo(clientInfo)
                .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                .roots(roots.list())
                .requestTimeout(properties.requestTimeout())
                .initializationTimeout(properties.initializationTimeout())
                .toolsChangeConsumer(tools -> {
                    toolsVersion.incrementAndGet();
                    log.info("MCP tools changed server={} tools={}", name, tools.size());
                })
                .build();
        McpSession opened;
        try {
            opened = McpSession.initialize(name, client);
        } catch (RuntimeException e) {
            log.warn("MCP initialize failed server={} url={} error={}", name, url(),
                    rootCause(e).getClass().getSimpleName());
            throw unavailable(McpErrorCode.CONNECTION_FAILED, "initialize", e);
        }
        session = opened;
        toolsVersion.incrementAndGet();
        lastFailure = null;
        log.info("MCP connected server={} url={} serverInfo={} version={} protocol={} roots={}", name, url(),
                opened.serverName(), opened.serverVersion(), opened.protocolVersion(), roots.size());
        return opened;
    }

    /** Drops the session if it is still the one that failed, so the next request opens a new one. */
    private synchronized void drop(McpSession failed) {
        if (failed != null && session == failed) {
            session = null;
            failed.close(false);
        }
    }

    @Override
    public synchronized void close() {
        if (session != null) {
            session.close(true);
            session = null;
        }
    }

    private McpUnavailableException unavailable(McpErrorCode code, String operation, RuntimeException e) {
        String message = switch (code) {
            case CONNECTION_FAILED -> "%s is not reachable at %s (%s). Start it, or fix devmate.mcp.servers.%s.url"
                    .formatted(displayName(), url(), connectProblem(e), name);
            case TIMEOUT -> "%s at %s did not answer %s within %ss".formatted(displayName(), url(), operation,
                    properties.requestTimeout().toSeconds());
            case SESSION_ERROR -> "%s at %s dropped the MCP session during %s and a new one failed too"
                    .formatted(displayName(), url(), operation);
            default -> tooLarge(e)
                    ? "%s at %s sent an answer to %s larger than max-response-size (%s)".formatted(displayName(), url(),
                            operation, properties.maxResponseSize())
                    : "%s at %s broke the transport during %s (%s)".formatted(displayName(), url(), operation,
                            rootCause(e).getClass().getSimpleName());
        };
        lastFailure = new Failure(code, message, Instant.now());
        return new McpUnavailableException(code, name, message, e);
    }

    private String connectProblem(Throwable e) {
        if (cause(e, ConnectException.class) != null) {
            return "connection refused";
        }
        if (cause(e, UnknownHostException.class) != null || cause(e, UnresolvedAddressException.class) != null) {
            return "unknown host";
        }
        if (cause(e, TimeoutException.class) != null || cause(e, HttpTimeoutException.class) != null) {
            return "no answer within the timeout";
        }
        McpError rejected = cause(e, McpError.class);
        if (rejected != null) {
            return "initialize rejected: " + clean(rejected.getMessage());
        }
        return rootCause(e).getClass().getSimpleName();
    }

    /** Which kind of breakage a failed request was (never {@code null}; JSON-RPC errors are handled before). */
    static McpErrorCode classify(Throwable e) {
        if (cause(e, McpTransportSessionNotFoundException.class) != null
                || cause(e, McpTransportSessionClosedException.class) != null) {
            return McpErrorCode.SESSION_ERROR;
        }
        if (cause(e, ConnectException.class) != null || cause(e, HttpConnectTimeoutException.class) != null
                || cause(e, UnknownHostException.class) != null || cause(e, UnresolvedAddressException.class) != null) {
            return McpErrorCode.CONNECTION_FAILED;
        }
        if (cause(e, TimeoutException.class) != null || cause(e, HttpTimeoutException.class) != null) {
            return McpErrorCode.TIMEOUT;
        }
        return McpErrorCode.TRANSPORT_ERROR;
    }

    /** The SDK refused a message over {@code max-response-size} (it only says so in the message). */
    static boolean tooLarge(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getClass().getSimpleName().equals("MaxSizeExceededException")
                    || (t.getMessage() != null && t.getMessage().contains("exceeds the maximum allowed size"))) {
                return true;
            }
        }
        return false;
    }

    static <X extends Throwable> X cause(Throwable e, Class<X> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    /** Server-provided text for a message: one line, bounded. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String line = text.replaceAll("\\p{Cntrl}+", " ").strip();
        return line.length() <= MAX_DETAIL_LENGTH ? line : line.substring(0, MAX_DETAIL_LENGTH) + "…";
    }
}
