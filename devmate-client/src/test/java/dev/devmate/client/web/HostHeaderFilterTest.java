package dev.devmate.client.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HostHeaderFilterTest {

    private final HostHeaderFilter filter = new HostHeaderFilter(List.of("localhost", "127.0.0.1", "[::1]", " "));

    @Test
    void loopbackNamesPassWhateverThePortOrCase() throws Exception {
        for (String host : List.of("localhost", "LOCALHOST", "127.0.0.1", "[::1]")) {
            var chain = new MockFilterChain();
            filter.doFilter(request(host), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(host).isNotNull();
        }
    }

    @Test
    void otherHostsAreRefusedBeforeAnyController() throws Exception {
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();

        filter.doFilter(request("evil.example"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("HOST_NOT_ALLOWED").doesNotContain("evil.example");
    }

    @Test
    void blankEntriesAllowNothing() throws Exception {
        var chain = new MockFilterChain();

        filter.doFilter(request(""), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNull();
    }

    private static MockHttpServletRequest request(String host) {
        var request = new MockHttpServletRequest("POST", "/api/mcp/tools/read_file/call");
        request.setServerName(host);
        return request;
    }
}
