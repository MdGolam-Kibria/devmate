package dev.devmate.client.mcp;

import java.net.URI;
import java.time.Instant;

/**
 * What DevMate knows about one configured server.
 *
 * @param state         {@code CONNECTED}: a session is open; {@code UNAVAILABLE}: the last attempt failed (see
 *                      {@code error}/{@code message}); {@code NOT_CONNECTED}: not tried yet, or the session was closed
 * @param serverName    from {@code initialize} (serverInfo), null unless connected; same for version and protocol
 * @param tools         tools discovered from this server (0 if not discovered)
 * @param error         the last failure, null when the last request worked
 */
public record McpServerStatus(String name, URI url, State state, String serverName, String serverVersion,
                              String protocolVersion, Instant connectedAt, int tools, McpErrorCode error,
                              String message) {

    public enum State { CONNECTED, NOT_CONNECTED, UNAVAILABLE }

    static McpServerStatus of(McpConnection connection, int tools) {
        McpSession session = connection.session();
        McpConnection.Failure failure = connection.lastFailure();
        State state = session != null ? State.CONNECTED : failure != null ? State.UNAVAILABLE : State.NOT_CONNECTED;
        return new McpServerStatus(connection.name(), connection.url(), state,
                session == null ? null : session.serverName(),
                session == null ? null : session.serverVersion(),
                session == null ? null : session.protocolVersion(),
                session == null ? null : session.openedAt(),
                tools,
                failure == null ? null : failure.code(),
                failure == null ? null : failure.message());
    }
}
