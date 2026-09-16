package de.mhus.vance.brain.chattheme;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.report.CssSanitizer;
import de.mhus.vance.brain.tools.report.CssScopePrefixer;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for chat session themes — the CSS a chat transcript is
 * styled with when the session's recipe carries a {@code webTheme}.
 *
 * <p>{@code GET /brain/{tenant}/projects/{project}/chat-themes/{name}/css}
 * serves the theme named in the path, resolved through the chat theme
 * cascade (project → {@code _vance} tenant → classpath default), then
 * filtered by the shared {@link CssSanitizer} and fenced to the chat
 * transcript root by the shared {@link CssScopePrefixer} — the same
 * serving pipeline the markdown document preview endpoint uses, with a
 * different scope class. The project is part of the path because the
 * cascade begins project-local; a tenant-global route would silently
 * cut the project layer.
 *
 * <p><b>Authorization.</b> A project READ check, the same visibility as
 * the recipe listing ({@code GET …/recipes/listed}): a chat theme is
 * styling configuration with recipe-level sensitivity, not content —
 * there is deliberately no per-document READ gate here, because the
 * served stylesheet is assembled from config layers, never from a
 * single user document.
 *
 * <p><b>Failure policy.</b> An invalid or unresolvable theme name does
 * not produce an HTTP error — the resolver degrades to the default
 * theme (see {@link ChatThemeResolver}). A 404 never reaches the
 * client, because the client injects the response as a
 * {@code <style>} element and has no error state for a styling problem
 * (fail-open, like the report theme endpoint).
 *
 * <p><b>Caching.</b> {@code Cache-Control: private, max-age=60} —
 * private because the response can differ per project layer, so a
 * shared (proxy) cache would serve one project's theme to another. The
 * ETag is a content hash (the classpath layer has no document
 * {@code storageId} to derive one from), so a re-validate with
 * {@code If-None-Match} answers 304 cheaply.
 */
@RestController
@RequiredArgsConstructor
public class ChatThemeController {

    /**
     * The scope class every served selector is fenced to. The web chat
     * transcript container carries this class (always, also for the
     * default theme) — the client and the server agree on it, and the
     * {@link CssScopePrefixer} doubling gives theme rules the same
     * specificity as the chat's Vue scoped styles.
     */
    static final String SCOPE_CLASS = ".chat-theme";

    private final ChatThemeResolver chatThemeResolver;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/projects/{project}/chat-themes/{name}/css")
    public ResponseEntity<String> themeCss(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        String resolved = chatThemeResolver.resolveStylesheet(tenant, project, name);
        String filtered = CssSanitizer.sanitize(resolved);
        String scoped = CssScopePrefixer.scope(filtered, SCOPE_CLASS);

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/css;charset=utf-8"))
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate())
                .eTag(contentHash(scoped))
                .body(scoped);
    }

    /**
     * MD5 over the served body, quoted — a stable content fingerprint
     * that works across all cascade layers (including the classpath
     * default, which has no document id to derive one from). MD5 is
     * fine here: the ETag is a cache key, not a security boundary.
     */
    private String contentHash(String content) {
        return '"' + DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8)) + '"';
    }
}
