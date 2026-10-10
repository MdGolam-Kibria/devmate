package dev.devmate.client.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

/** How failures surfaced by the SDK (often wrapped by Reactor) are classified. */
class McpConnectionTest {

    @Test
    void classifiesWrappedSdkFailures() {
        assertThat(McpConnection.classify(wrapped(new ConnectException()))).isEqualTo(McpErrorCode.CONNECTION_FAILED);
        assertThat(McpConnection.classify(wrapped(new UnknownHostException("x"))))
                .isEqualTo(McpErrorCode.CONNECTION_FAILED);
        assertThat(McpConnection.classify(wrapped(new HttpConnectTimeoutException("x"))))
                .isEqualTo(McpErrorCode.CONNECTION_FAILED);
        assertThat(McpConnection.classify(wrapped(new TimeoutException()))).isEqualTo(McpErrorCode.TIMEOUT);
        assertThat(McpConnection.classify(wrapped(new HttpTimeoutException("x")))).isEqualTo(McpErrorCode.TIMEOUT);
        assertThat(McpConnection.classify(new McpTransportSessionNotFoundException("id")))
                .isEqualTo(McpErrorCode.SESSION_ERROR);
        assertThat(McpConnection.classify(new McpTransportException("Invalid request. Status code: 500")))
                .isEqualTo(McpErrorCode.TRANSPORT_ERROR);
        assertThat(McpConnection.classify(wrapped(new IOException("reset")))).isEqualTo(McpErrorCode.TRANSPORT_ERROR);
    }

    @Test
    void sessionLossWinsOverItsCause() {
        var lost = new McpTransportSessionNotFoundException("id");
        lost.initCause(new ConnectException());

        assertThat(McpConnection.classify(lost)).isEqualTo(McpErrorCode.SESSION_ERROR);
    }

    @Test
    void recognisesTheSdkSizeLimit() {
        assertThat(McpConnection.tooLarge(wrapped(new McpTransportException(
                "Inbound response body exceeds the maximum allowed size of 2048 bytes")))).isTrue();
        assertThat(McpConnection.tooLarge(new McpTransportException("Invalid request. Status code: 500"))).isFalse();
    }

    @Test
    void serverTextIsOneBoundedLine() {
        assertThat(McpConnection.clean("a\r\nb\u0000c")).isEqualTo("a b c");
        assertThat(McpConnection.clean("x".repeat(500))).hasSize(201).endsWith("…");
        assertThat(McpConnection.clean(null)).isEmpty();
    }

    @Test
    void displayNameCapitalisesTheConfiguredName() {
        var connection = new McpConnection("files", null, null, null, null);

        assertThat(connection.displayName()).isEqualTo("Files MCP server");
    }

    private static RuntimeException wrapped(Exception e) {
        return new RuntimeException("Client failed to initialize", Exceptions.propagate(e));
    }
}
