package dev.devmate.client.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses requests whose {@code Host} isn't one of {@code devmate.http.allowed-hosts} (default: the loopback names).
 * Binding to 127.0.0.1 keeps other machines out, but not DNS rebinding: a web page whose domain re-resolves to
 * 127.0.0.1 can call DevMate same-origin and read the answers (files via {@code /api/mcp}). Such requests still carry
 * the page's domain in {@code Host}, so they stop here. Add your host name when binding DevMate to another address.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class HostHeaderFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HostHeaderFilter.class);

    private final Set<String> allowed;

    public HostHeaderFilter(@Value("${devmate.http.allowed-hosts:localhost,127.0.0.1,[::1]}") List<String> allowed) {
        this.allowed = allowed.stream().map(String::strip).filter(h -> !h.isEmpty())
                .map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // getServerName() is the Host header without its port (or the local address when there is no Host header).
        if (allowed.contains(request.getServerName().toLowerCase(Locale.ROOT))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Request refused: Host not in devmate.http.allowed-hosts");   // the value itself is untrusted
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"HOST_NOT_ALLOWED\",\"message\":\"DevMate only answers requests "
                + "addressed to an allowed host (devmate.http.allowed-hosts)\"}");
    }
}
