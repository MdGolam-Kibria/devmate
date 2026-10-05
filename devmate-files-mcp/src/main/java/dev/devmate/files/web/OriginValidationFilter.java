package dev.devmate.files.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DNS-rebinding protection required by the MCP Streamable HTTP spec: a request that carries an {@code Origin}
 * header (i.e. comes from a browser) is rejected unless that origin is allow-listed in
 * {@code devmate.http.allowed-origins}. Non-browser MCP clients send no {@code Origin} and pass through.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class OriginValidationFilter extends OncePerRequestFilter {

    private final Set<String> allowedOrigins;

    public OriginValidationFilter(@Value("${devmate.http.allowed-origins:}") List<String> allowedOrigins) {
        this.allowedOrigins = Set.copyOf(allowedOrigins);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"ORIGIN_NOT_ALLOWED\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
