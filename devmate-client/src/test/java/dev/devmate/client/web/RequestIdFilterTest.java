package dev.devmate.client.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private static final String UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void reusesWellFormedInboundId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "abc-123_X.y");
        var response = new MockHttpServletResponse();
        var seenInMdc = new AtomicReference<String>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInMdc.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123_X.y");
        assertThat(seenInMdc).hasValue("abc-123_X.y");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("MDC cleared after request").isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bad id", "inject\nFAKE LOG LINE", "<script>", "way-too-long-0123456789012345678901234567890123456789012345678901234"})
    void replacesMissingOrMalformedIdWithUuid(String inbound) {
        assertThat(RequestIdFilter.resolve(inbound)).matches(UUID_SHAPE).isNotEqualTo(inbound);
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        var request = new MockHttpServletRequest();
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), chain))
                .hasMessage("boom");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
