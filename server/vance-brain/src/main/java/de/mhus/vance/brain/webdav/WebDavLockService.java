package de.mhus.vance.brain.webdav;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import io.milton.http.LockInfo;
import io.milton.http.LockResult;
import io.milton.http.LockTimeout;
import io.milton.http.LockToken;
import io.milton.resource.LockableResource;
import io.milton.resource.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Handles the WebDAV {@code LOCK} and {@code UNLOCK} methods directly from the
 * servlet layer, outside milton's handler pipeline. milton CE ships no level-2
 * method handlers (they live in the AGPL enterprise jar), and registering a
 * custom protocol means reaching into {@code HttpManagerBuilder} internals — so
 * we dispatch these two verbs here, reusing the resources' {@link LockableResource}
 * implementation (→ Redis lock manager) and doing Basic-Auth + authorisation via
 * {@link VanceWebDavSecurityManager}. milton still advertises {@code DAV: 1,2}
 * (resources are lockable) and enforces locks on writes. See
 * {@code planning/webdav-support.md} §1, §5.
 */
@Slf4j
public class WebDavLockService {

    private final DocumentResourceFactory factory;

    public WebDavLockService(DocumentResourceFactory factory) {
        this.factory = factory;
    }

    public void handleLock(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<WebDavPaths.Coords> parsed = WebDavPaths.parse(request.getRequestURI());
        if (parsed.isEmpty() || parsed.get().project() == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        WebDavPaths.Coords coords = parsed.get();
        DavPrincipal principal = authenticate(request, coords.tenantId());
        if (principal == null) {
            challenge(response);
            return;
        }

        Resource resource = factory.resolve(coords);
        boolean created = false;
        if (resource == null) {
            // Lock-null: the target doesn't exist yet (macOS locks before the
            // first PUT). Materialise an empty target so the lock has something
            // to attach to and the subsequent PUT replaces it.
            if (!createEmptyTarget(coords, principal, response)) {
                return;
            }
            resource = factory.resolve(coords);
            created = true;
            if (resource == null) {
                response.sendError(HttpServletResponse.SC_CONFLICT);
                return;
            }
        }
        if (!(resource instanceof AbstractDavResource dav) || !(resource instanceof LockableResource lockable)) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        if (!factory.securityManager().check(principal, dav, Action.WRITE)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        LockTimeout timeout = parseTimeout(request);
        String ifHeader = request.getHeader("If");
        LockResult result;
        try {
            if (ifHeader != null && !ifHeader.isBlank()) {
                result = lockable.refreshLock(parseToken(ifHeader), timeout);
            } else {
                LockInfo info = new LockInfo(
                        LockInfo.LockScope.EXCLUSIVE, LockInfo.LockType.WRITE,
                        principal.username(), depth(request));
                result = lockable.lock(timeout, info);
            }
        } catch (Exception e) {
            log.debug("webdav LOCK failed for {}: {}", request.getRequestURI(), e.toString());
            response.setStatus(SC_LOCKED);
            return;
        }
        if (result == null || !result.isSuccessful()) {
            response.setStatus(SC_LOCKED);
            return;
        }
        LockToken token = result.getLockToken();
        response.setStatus(created ? HttpServletResponse.SC_CREATED : HttpServletResponse.SC_OK);
        response.setHeader("Lock-Token", "<opaquelocktoken:" + token.tokenId + ">");
        response.setContentType("application/xml; charset=utf-8");
        response.getWriter().write(lockXml(token, request.getRequestURI()));
    }

    public void handleUnlock(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<WebDavPaths.Coords> parsed = WebDavPaths.parse(request.getRequestURI());
        if (parsed.isEmpty() || parsed.get().project() == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        WebDavPaths.Coords coords = parsed.get();
        DavPrincipal principal = authenticate(request, coords.tenantId());
        if (principal == null) {
            challenge(response);
            return;
        }
        Resource resource = factory.resolve(coords);
        if (resource instanceof LockableResource lockable) {
            try {
                lockable.unlock(parseToken(request.getHeader("Lock-Token")));
            } catch (Exception e) {
                log.debug("webdav UNLOCK ignored for {}: {}", request.getRequestURI(), e.toString());
            }
        }
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private boolean createEmptyTarget(WebDavPaths.Coords coords, DavPrincipal principal, HttpServletResponse response)
            throws IOException {
        String leaf = leafOf(coords.path());
        try {
            if (factory.properties().isSidecar(leaf)) {
                factory.sidecarStore().put(coords.tenantId(), coords.project(), coords.path(), new byte[0]);
            } else {
                factory.documentService().create(
                        coords.tenantId(), coords.project(), coords.path(),
                        null, null, DocumentService.mimeFromPath(coords.path()),
                        new ByteArrayInputStream(new byte[0]), principal.username());
            }
            return true;
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            // Race — it exists now; caller re-resolves.
            return true;
        } catch (DocumentService.DocumentLockedException e) {
            response.setStatus(SC_LOCKED);
            return false;
        }
    }

    private @Nullable DavPrincipal authenticate(HttpServletRequest request, String tenantId) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return factory.securityManager().authenticate(
                tenantId, decoded.substring(0, colon), decoded.substring(colon + 1));
    }

    private void challenge(HttpServletResponse response) {
        response.setHeader("WWW-Authenticate", "Basic realm=\"" + factory.properties().getRealm() + "\"");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private static LockTimeout parseTimeout(HttpServletRequest request) {
        String header = request.getHeader("Timeout");
        if (header == null || header.isBlank()) {
            return new LockTimeout(null);
        }
        String first = header.split(",")[0].trim();
        if (first.equalsIgnoreCase("Infinite")) {
            return new LockTimeout(Long.MAX_VALUE);
        }
        if (first.regionMatches(true, 0, "Second-", 0, 7)) {
            try {
                return new LockTimeout(Long.parseLong(first.substring(7).trim()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return new LockTimeout(null);
    }

    private static LockInfo.LockDepth depth(HttpServletRequest request) {
        String header = request.getHeader("Depth");
        return "infinity".equalsIgnoreCase(header) ? LockInfo.LockDepth.INFINITY : LockInfo.LockDepth.ZERO;
    }

    /** Strip {@code (}, {@code <>}, {@code )} wrapping and the {@code opaquelocktoken:} scheme. */
    static String parseToken(@Nullable String header) {
        if (header == null) {
            return "";
        }
        String s = header.trim();
        int lt = s.indexOf('<');
        int gt = s.indexOf('>');
        if (lt >= 0 && gt > lt) {
            s = s.substring(lt + 1, gt);
        }
        if (s.startsWith("opaquelocktoken:")) {
            s = s.substring("opaquelocktoken:".length());
        }
        return s.trim();
    }

    private static String lockXml(LockToken token, String href) {
        String timeout = "Infinite";
        Long seconds = token.timeout == null ? null : token.timeout.getSeconds();
        if (seconds != null && seconds != Long.MAX_VALUE) {
            timeout = "Second-" + seconds;
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<D:prop xmlns:D=\"DAV:\">\n"
                + "  <D:lockdiscovery>\n"
                + "    <D:activelock>\n"
                + "      <D:locktype><D:write/></D:locktype>\n"
                + "      <D:lockscope><D:exclusive/></D:lockscope>\n"
                + "      <D:depth>0</D:depth>\n"
                + "      <D:timeout>" + timeout + "</D:timeout>\n"
                + "      <D:locktoken><D:href>opaquelocktoken:" + token.tokenId + "</D:href></D:locktoken>\n"
                + "      <D:lockroot><D:href>" + xmlEscape(href) + "</D:href></D:lockroot>\n"
                + "    </D:activelock>\n"
                + "  </D:lockdiscovery>\n"
                + "</D:prop>\n";
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String leafOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** {@code 423 Locked} — not exposed as a constant on {@link HttpServletResponse}. */
    private static final int SC_LOCKED = 423;
}
