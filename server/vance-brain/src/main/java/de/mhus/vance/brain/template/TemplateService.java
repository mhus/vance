package de.mhus.vance.brain.template;

import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.form.FormValidator;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.TimezoneResolver;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Applies a {@link ResolvedTemplate}: validates the form, resolves the
 * target filename + MIME, renders the Pebble body and writes exactly one
 * new document through {@link DocumentService}.
 *
 * <p>Overwrite protection is delegated to {@code DocumentService.create},
 * which throws {@link DocumentService.DocumentAlreadyExistsException} when
 * the target path already exists — the controller maps that to HTTP 409.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateService {

    private final DocumentService documentService;
    private final PromptTemplateRenderer templateRenderer;
    private final FormValidator formValidator;
    private final TimezoneResolver timezoneResolver;

    /** Path + MIME of the created document. */
    public record AppliedTemplate(String path, String mimeType) {}

    /**
     * Renders and writes the template.
     *
     * @throws de.mhus.vance.shared.form.FormValidationException when the
     *         form values are malformed (→ HTTP 400 via advice)
     * @throws DocumentService.DocumentAlreadyExistsException when the
     *         target document already exists (→ HTTP 409)
     * @throws IllegalStateException on a missing free-mode name or a
     *         body render failure (→ HTTP 400)
     */
    public AppliedTemplate apply(
            ResolvedTemplate template,
            String folder,
            @Nullable String requestedName,
            Map<String, Object> values,
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId,
            @Nullable String lang) {

        if (!template.fields().isEmpty()) {
            formValidator.validate(template.fields(), values);
        }

        String bodyExt = template.bodyExtension();
        String filename = resolveFilename(template, requestedName, bodyExt);
        String targetPath = joinPath(folder, filename);
        String mime = template.typeOverride() != null
                ? template.typeOverride()
                : DocumentService.mimeFromPath(filename);

        String content = renderBody(template, values, filename, userId, projectId, lang, tenantId);

        String project = (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.TENANT_PROJECT_NAME
                : projectId;

        DocumentDocument doc = documentService.create(
                tenantId, project, targetPath,
                /*title*/ null, /*tags*/ null, mime,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                /*createdBy*/ userId);

        log.info("Template '{}' applied tenant='{}' project='{}' path='{}'",
                template.name(), tenantId, project, doc.getPath());
        return new AppliedTemplate(
                doc.getPath(),
                doc.getMimeType() != null ? doc.getMimeType() : mime);
    }

    /**
     * Determines the created document's filename. FIXED templates use
     * {@code name.value} verbatim (folder is user-chosen). FREE templates
     * use the requested stem; the body extension is appended when the
     * stem carries no extension of its own.
     */
    private String resolveFilename(ResolvedTemplate template, @Nullable String requestedName, String bodyExt) {
        if (template.nameMode() == TemplateNameMode.FIXED) {
            // Guaranteed non-null by the loader for FIXED mode.
            return template.nameValue();
        }
        String stem = requestedName == null ? "" : requestedName.trim();
        stem = stem.replaceAll("^/+|/+$", "");
        if (stem.isEmpty()) {
            throw new IllegalStateException("a filename is required for this template");
        }
        // A stem without its own '.' extension gets the body extension appended;
        // a user who typed an explicit extension keeps it.
        if (!stem.contains(".") && !bodyExt.isEmpty()) {
            stem = stem + "." + bodyExt;
        }
        return stem;
    }

    private String renderBody(
            ResolvedTemplate template,
            Map<String, Object> values,
            String filename,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String lang,
            String tenantId) {
        Map<String, Object> ctx = new HashMap<>(values);
        ctx.putIfAbsent("name", stripExtension(filename));
        ctx.putIfAbsent("date", today(tenantId, userId));
        ctx.putIfAbsent("user", userId == null ? "" : userId);
        ctx.putIfAbsent("project", projectId == null ? "" : projectId);
        ctx.putIfAbsent("lang", lang == null ? "" : lang);
        try {
            String rendered = templateRenderer.render(template.bodyContent(), ctx);
            return rendered == null ? "" : rendered;
        } catch (PromptTemplateException e) {
            throw new IllegalStateException(
                    "template body render failed: " + e.getMessage(), e);
        }
    }

    /** Renders the FREE-mode {@code name.default} suggestion (no extension). Empty when absent. */
    public String renderNameDefault(
            ResolvedTemplate template, @Nullable String userId,
            @Nullable String projectId, @Nullable String lang, String tenantId) {
        String tpl = template.nameDefaultTemplate();
        if (tpl == null) return "";
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("date", today(tenantId, userId));
        ctx.put("user", userId == null ? "" : userId);
        ctx.put("project", projectId == null ? "" : projectId);
        ctx.put("lang", lang == null ? "" : lang);
        try {
            String rendered = templateRenderer.render(tpl, ctx);
            return rendered == null ? "" : rendered.trim();
        } catch (PromptTemplateException e) {
            log.warn("Template '{}' name.default render failed: {}", template.name(), e.getMessage());
            return "";
        }
    }

    private String today(String tenantId, @Nullable String userId) {
        ZoneId zone = timezoneResolver.zoneId(tenantId, userId);
        return LocalDate.now(zone).toString();
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    private static String joinPath(String folder, String filename) {
        String f = folder == null ? "" : folder.trim().replaceAll("^/+|/+$", "");
        String name = filename.replaceAll("^/+", "");
        return f.isEmpty() ? name : f + "/" + name;
    }
}
