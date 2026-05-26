package de.mhus.vance.brain.tools.web;

import de.mhus.vance.api.web.LinkPreviewDto;
import de.mhus.vance.shared.access.AccessFilterBase;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-side OpenGraph proxy for the Web-UI. Browser fetches of
 * {@code og:title} on arbitrary origins die on CORS; this endpoint
 * eats that cross-origin call once and serves cached OG-data with
 * a friendly {@code Access-Control-Allow-Origin} answer.
 *
 * <p>Authorisation: any authenticated user of the tenant may query.
 * The cached previews are tenant-agnostic (OG-tags are public
 * metadata), but the endpoint is JWT-gated so an unauthenticated
 * client cannot abuse Vance as a CORS-proxy for arbitrary URLs.
 *
 * <p>Always returns 200 with a populated DTO — even when the upstream
 * fetch failed. The DTO's {@code ok} field tells the UI which card
 * variant to render. This avoids a noisy 4xx/5xx fan-out on the
 * client side just because one of many links in a chat reply is
 * dead — the rendering layer cares about the verdict, not HTTP
 * semantics.
 */
@RestController
@RequestMapping("/brain/{tenant}/link-preview")
@RequiredArgsConstructor
@Slf4j
public class LinkPreviewController {

    private final LinkPreviewService service;

    @GetMapping
    public LinkPreviewDto preview(
            @PathVariable("tenant") String tenant,
            @RequestParam("url") String url,
            HttpServletRequest httpRequest) {
        requireAuth(httpRequest);
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Query parameter 'url' is required");
        }
        // Project + process scope are not part of the request — the
        // preview cache is tenant-agnostic, and the settings cascade
        // is rarely interesting for OG-fetching. If a tenant ever
        // needs per-project timeouts, add them as optional query
        // params and pass through here.
        return service.preview(url, tenant, null, null);
    }

    private static void requireAuth(HttpServletRequest req) {
        Object u = req.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "No authenticated user");
        }
    }
}
