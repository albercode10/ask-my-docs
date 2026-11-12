package com.alber.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers only requests addressed to localhost, until the API has authentication.
 *
 * <p>{@code server.address=127.0.0.1} keeps other machines out, but a web page open in the developer's browser can
 * point its own domain at 127.0.0.1 (DNS rebinding) and call the API as a same-origin request. Its requests still
 * carry that domain in the {@code Host} header, so they are rejected here with 403.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class LocalhostOnlyFilter extends OncePerRequestFilter {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName();
        if (host == null || !LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            log.warn("Rejected a request for host '{}': the API only answers localhost.", host);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This API only answers requests for localhost.");
            return;
        }
        chain.doFilter(request, response);
    }
}
