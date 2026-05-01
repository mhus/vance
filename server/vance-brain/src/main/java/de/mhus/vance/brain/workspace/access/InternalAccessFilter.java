package de.mhus.vance.brain.workspace.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates pod-internal requests via a shared {@code X-Vance-Internal-Token}
 * header. Only enforced on {@code /internal/**} paths; everything else passes
 * straight through to the regular access filter chain. The header is compared
 * against {@code vance.internal.token} in constant time so a timing oracle
 * can't probe the secret byte-by-byte.
 *
 * <p>Layer 2 endpoints (e.g. workspace tree/file proxying) trust this filter
 * for caller authentication and therefore do <em>not</em> re-validate the
 * user's JWT — the proxying Layer 1 controller already did that. K8s
 * NetworkPolicy must keep this route off the external ingress.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class InternalAccessFilter extends OncePerRequestFilter {

    public static final String INTERNAL_PATH_PREFIX = "/internal/";
    public static final String HEADER_INTERNAL_TOKEN = "X-Vance-Internal-Token";

    private final byte[] expectedToken;

    public InternalAccessFilter(@Value("${vance.internal.token:}") String token) {
        this.expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (this.expectedToken.length == 0) {
            log.warn("vance.internal.token is empty — /internal/** routes will reject every caller");
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String uri = request.getRequestURI();
        if (!uri.startsWith(INTERNAL_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String presented = request.getHeader(HEADER_INTERNAL_TOKEN);
        if (!matches(presented)) {
            log.debug("Rejecting internal request {} {} — invalid or missing internal token",
                    request.getMethod(), uri);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write("401 Unauthorized (internal)\n");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(@Nullable String presented) {
        if (presented == null || presented.isEmpty() || expectedToken.length == 0) {
            return false;
        }
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presentedBytes, expectedToken);
    }
}
