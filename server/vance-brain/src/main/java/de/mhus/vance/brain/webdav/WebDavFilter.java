package de.mhus.vance.brain.webdav;

import io.milton.http.HttpManager;
import io.milton.servlet.ServletRequest;
import io.milton.servlet.ServletResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Routes {@code /brain/{tenant}/webdav/…} requests to the milton
 * {@link HttpManager}. A servlet URL-pattern can't isolate the variable tenant
 * segment in the middle of the path, and Spring MVC can't route the WebDAV verbs
 * (PROPFIND/MKCOL/…), so a filter matching the path prefix delegates to milton
 * and short-circuits; everything else falls through to the normal chain. See
 * {@code planning/webdav-support.md} §2.
 *
 * <p>{@code BrainAccessFilter} deliberately bypasses this path (WebDAV uses
 * Basic-Auth, not the bearer JWT), so authentication happens inside milton via
 * {@link VanceWebDavSecurityManager}.
 */
public class WebDavFilter extends OncePerRequestFilter {

    private final HttpManager httpManager;
    private final WebDavLockService lockService;

    public WebDavFilter(HttpManager httpManager, WebDavLockService lockService) {
        this.httpManager = httpManager;
        this.lockService = lockService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!WebDavPaths.WEBDAV_PATH.matcher(request.getRequestURI()).matches()) {
            chain.doFilter(request, response);
            return;
        }
        // LOCK/UNLOCK are serviced outside milton (CE has no level-2 handlers);
        // everything else goes through the milton pipeline.
        String method = request.getMethod();
        if ("LOCK".equalsIgnoreCase(method)) {
            lockService.handleLock(request, response);
            response.flushBuffer();
            return;
        }
        if ("UNLOCK".equalsIgnoreCase(method)) {
            lockService.handleUnlock(request, response);
            response.flushBuffer();
            return;
        }
        ServletRequest miltonRequest = new ServletRequest(request, request.getServletContext());
        ServletResponse miltonResponse = new ServletResponse(response);
        httpManager.process(miltonRequest, miltonResponse);
        response.flushBuffer();
    }
}
