package de.mhus.vance.brain.tools.report;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentRef;
import de.mhus.vance.shared.document.DocumentRefContext;
import de.mhus.vance.shared.document.DocumentRefResolver;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.SecurityContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Resolves the CSS stylesheet(s) for a PDF report render and assembles
 * them into a single {@code <style>} body — order matters: later rules win
 * in the CSS cascade, so the default theme is first, an optional named
 * {@code theme:} second, and an optional {@code css:} document reference
 * last (so a per-document override beats a per-project theme which beats
 * the bundled default).
 *
 * <p>Three layers, three sources:
 * <ol>
 *   <li><b>Default</b> — {@code vance-defaults/_vance/report-themes/default.css}
 *       on the classpath. Always present. Carries the {@code @page} setup and
 *       base typography. Extracted verbatim from the former hardcoded
 *       {@code printCss()} string, so a report without {@code theme:}/{@code css:}
 *       renders identically to before this feature existed.</li>
 *   <li><b>Theme</b> — a named stylesheet under
 *       {@code _vance/report-themes/<name>.css}, resolved through the
 *       {@link DocumentService#lookupCascade(String, String, String)}
 *       (project &rarr; {@code _vance} tenant &rarr; classpath fallback).
 *       Activated by {@code theme: <name>} in the markdown front matter.
 *       The name is validated against {@link #THEME_NAME} — no path
 *       segments, no traversal; the cascade path is built by the resolver,
 *       never from user input.</li>
 *   <li><b>CSS reference</b> — a {@code vance:} document reference (or a bare
 *       path) pointing at a CSS document in the caller's project (or, with
 *       a {@code //authority/} form, another project). Activated by
 *       {@code css: vance:/styles/round-borders.css} in the front matter.
 *       Resolved by {@link DocumentRefResolver} so the same grammar that
 *       governs skill/guard references governs report stylesheets, then
 *       read through {@link DocumentService#findByPath} — <b>after</b> a
 *       READ check on the resolved document against the caller's subject.
 *       The resolver computes and does not authorise, and the grammar it
 *       implements reaches other projects, so this layer is the one place
 *       in the three that has an access question at all.</li>
 * </ol>
 *
 * <p>Failure policy is <b>fail-open on the optional layers</b>: a missing
 * theme file or a dangling {@code css:} reference logs a warning and falls
 * back to the previous layer (default &rarr; default+theme &rarr;
 * default+theme+css). The default layer is bundled, so it never misses —
 * a missing default is an internal error and surfaces as an empty default
 * block plus a WARN. The render never aborts because of a stylesheet
 * problem: a broken theme should not block a working PDF.
 *
 * <p>The resolved CSS is plain text injected into a {@code <style>} block
 * by {@link PdfReportRenderer}; it is never exposed to the openhtmltopdf
 * URI resolver, so a stylesheet cannot pull in external resources by
 * itself (the {@code SAFE_URI_RESOLVER} governs {@code <img src>}/links
 * in the body, not CSS {@code url()} — openhtmltopdf resolves those
 * against the base URI, which is {@code null}, so they are skipped too).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportThemeResolver {

    /** Path prefix for theme files inside the document cascade. */
    static final String THEME_PATH_PREFIX = "_vance/report-themes/";

    /** Classpath location of the bundled default theme. */
    private static final String DEFAULT_RESOURCE =
            "vance-defaults/_vance/report-themes/default.css";

    /**
     * Theme name validator — lowercase letters, digits, hyphen. Deliberately
     * forbids slashes, dots, and anything that could traverse or escape the
     * {@code _vance/report-themes/} folder. A rejected name is a user-facing
     * error (handled by the caller, not here, so the message can carry the
     * offending name).
     */
    static final Pattern THEME_NAME = Pattern.compile("[a-z0-9-]+");

    private final DocumentService documentService;
    private final DocumentRefResolver documentRefResolver;
    private final ResourcePatternResolver resourcePatternResolver;
    private final PermissionService permissionService;

    /**
     * Assemble the full CSS body for a render: default, then theme (if
     * {@code themeName} resolves), then the referenced stylesheet (if
     * {@code cssRef} resolves). The three are concatenated with a blank
     * line between them; each layer is optional except the default.
     *
     * @param tenantId   caller's tenant — for theme cascade and, when
     *                   {@code cssRef} is project-relative, for the document
     *                   lookup. Must not be blank.
     * @param projectName caller's active project name. The {@code cssRef}
     *                   is resolved against the project root (relative refs
     *                   land in the project, absolute refs carry their own
     *                   authority). Must not be blank.
     * @param themeName  optional theme name from the front matter; validated
     *                   against {@link #THEME_NAME}, missing/blank/invalid
     *                   &rarr; skipped (invalid logs a WARN).
     * @param cssRef     optional {@code vance:} document reference or bare
     *                   path to a CSS document; missing/blank &rarr; skipped,
     *                   unresolvable/dangling &rarr; skipped with WARN.
     * @param subject    who is asking. Required for the {@code cssRef} layer —
     *                   {@code null} drops it. See {@link #loadCssRef}.
     * @return the concatenated CSS, never {@code null}; at minimum the
     *         bundled default (which may itself be empty on an internal
     *         misconfiguration, with a WARN logged).
     */
    public String resolveStylesheet(
            String tenantId,
            String projectName,
            @Nullable String themeName,
            @Nullable String cssRef,
            @Nullable SecurityContext subject) {

        StringBuilder css = new StringBuilder();
        css.append(loadDefault());

        String themeCss = loadTheme(tenantId, projectName, themeName);
        if (themeCss != null) {
            css.append('\n').append(themeCss);
        }

        String refCss = loadCssRef(tenantId, projectName, cssRef, subject);
        if (refCss != null) {
            css.append('\n').append(refCss);
        }
        return css.toString();
    }

    // ──────────────────── default layer (classpath, always) ───────────────

    private String loadDefault() {
        String content = readClasspath(DEFAULT_RESOURCE);
        if (content == null) {
            log.warn("Bundled default report theme not found at classpath:{} — "
                    + "PDF will render without base styles. This is an internal "
                    + "misconfiguration.", DEFAULT_RESOURCE);
            return "";
        }
        return content;
    }

    // ──────────────────── theme layer (cascade, optional) ──────────────────

    private @Nullable String loadTheme(
            String tenantId, String projectName, @Nullable String themeName) {
        if (themeName == null || themeName.isBlank()) return null;
        String name = themeName.trim();
        if (!THEME_NAME.matcher(name).matches()) {
            log.warn("Report theme name '{}' is invalid (must match [a-z0-9-]+) "
                    + "— theme skipped.", name);
            return null;
        }
        String path = THEME_PATH_PREFIX + name + ".css";
        Optional<LookupResult> hit = documentService.lookupCascade(
                tenantId, projectName, path);
        if (hit.isEmpty()) {
            log.warn("Report theme '{}' not found at '{}' in any cascade layer "
                    + "(project → _vance → classpath) — falling back to default.", name, path);
            return null;
        }
        return hit.get().content();
    }

    // ──────────────────── css-ref layer (document, optional) ───────────────

    private @Nullable String loadCssRef(
            String tenantId,
            String projectName,
            @Nullable String cssRef,
            @Nullable SecurityContext subject) {
        if (cssRef == null || cssRef.isBlank()) return null;
        String ref = cssRef.trim();

        // Before anything is resolved: no subject, no read. Fail closed
        // rather than fall through to a permissive default — a caller that
        // does not name who is asking is a caller that forgot to.
        if (subject == null) {
            log.warn("Report css reference '{}' was given without a subject "
                    + "— css layer skipped (nobody to read it on behalf of).", ref);
            return null;
        }

        DocumentRefContext ctx = DocumentRefContext.root(projectName);
        DocumentRef resolved;
        try {
            resolved = documentRefResolver.resolve(ref, ctx);
        } catch (de.mhus.vance.shared.document.DocumentRefException e) {
            log.warn("Report css reference '{}' could not be resolved: {} "
                    + "— css layer skipped.", ref, e.getMessage());
            return null;
        }

        // The resolver is pure computation: `vance://other/x.css` resolves
        // just as happily as a path in the caller's own project, and a
        // cross-project reference implies no access whatsoever. Without this
        // check the endpoint reads any document of the tenant on request —
        // the scope prefixer passes text without braces through unchanged, so
        // whatever is in the file comes back nearly verbatim.
        //
        // Checked rather than enforced: a refused stylesheet is a styling
        // problem and drops its layer, exactly like a dangling ref. Turning
        // it into a 403 would fail a render that is otherwise fine.
        if (!permissionService.check(
                subject,
                new de.mhus.vance.shared.permission.Resource.Document(
                        tenantId, resolved.projectId(), resolved.path()),
                Action.READ)) {
            log.warn("Report css reference '{}' resolved to '{}/{}', which '{}' may not "
                            + "read — css layer skipped.",
                    ref, resolved.projectId(), resolved.path(), subject.subjectId());
            return null;
        }

        // The ref may target another project (//authority/…). The resolver
        // returns that project's name in DocumentRef.projectId; we look it
        // up in the same tenant. findByPath honours the mount namespace, but
        // a CSS ref into _ext/ is not meaningful here — it would just resolve
        // or not, same as any document.
        Optional<DocumentDocument> doc = documentService.findByPath(
                tenantId, resolved.projectId(), resolved.path());
        if (doc.isEmpty()) {
            log.warn("Report css reference '{}' resolved to '{}/{}' but no such "
                    + "document exists — css layer skipped.",
                    ref, resolved.projectId(), resolved.path());
            return null;
        }
        return readDocumentContent(doc.get());
    }

    private @Nullable String readDocumentContent(DocumentDocument doc) {
        String inline = documentService.readContent(doc);
        if (inline != null) return inline;
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read css document '{}': {} — css layer skipped.",
                    doc.getPath(), e.toString());
            return null;
        }
    }

    private @Nullable String readClasspath(String resourcePath) {
        try {
            Resource resource = resourcePatternResolver.getResource(
                    "classpath:" + resourcePath);
            if (!resource.exists() || !resource.isReadable()) return null;
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to read classpath resource '{}': {}",
                    resourcePath, e.toString());
            return null;
        }
    }
}
