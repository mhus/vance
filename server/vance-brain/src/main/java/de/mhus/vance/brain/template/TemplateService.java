package de.mhus.vance.brain.template;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplicationRegistry;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.form.FormValidator;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.settings.TimezoneResolver;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * <p>A template that declares {@code app:} takes the second path: instead of
 * rendering a body it calls that application's
 * {@link VanceApplication#create(VanceApplication.CreateContext) create()},
 * which owns the manifest format and also produces the derived artefacts
 * ({@code _index.md}, a first view, …) that a one-document template cannot
 * write. Without it the surface way of creating an app and the tool way
 * produce different folders.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateService {

    private final DocumentService documentService;
    private final PromptTemplateRenderer templateRenderer;
    private final FormValidator formValidator;
    private final TimezoneResolver timezoneResolver;
    private final VanceApplicationRegistry applicationRegistry;
    private final PermissionService permissionService;

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
            de.mhus.vance.shared.permission.SecurityContext subject,
            @Nullable String lang) {

        if (!template.fields().isEmpty()) {
            formValidator.validate(template.fields(), values);
        }
        Map<String, Object> typedValues = coerceByFieldType(template.fields(), values);

        if (template.isApp()) {
            return applyApp(template, folder, typedValues, tenantId, projectId, subject);
        }

        // The target folder is caller-chosen, so this is a user-driven write:
        // it must carry the authenticated subject with WriteReason.USER so the
        // permission provider applies the normal role check (R4: a reserved
        // _vance/ folder needs ADMIN). Passing WriteActor.SYSTEM here would
        // fail-open and let a WRITER plant privileged control-plane docs.
        String userId = subject.subjectType() == de.mhus.vance.shared.permission.SubjectType.USER
                ? subject.subjectId()
                : null;

        String bodyExt = template.bodyExtension();
        String filename = resolveFilename(template, requestedName, bodyExt);
        // A template that declares a folder wins over the caller's: it does so
        // because its output is only read at that path (a loader with a fixed
        // prefix), and honouring the caller there would write a file nobody
        // reads. Templates without one keep the historical behaviour.
        String targetFolder = template.folder() != null ? template.folder() : folder;
        String targetPath = joinPath(targetFolder, filename);
        String mime = template.typeOverride() != null
                ? template.typeOverride()
                : DocumentService.mimeFromPath(filename);

        String content = renderBody(template, typedValues, filename, userId, projectId, lang, tenantId);

        String project = (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.TENANT_PROJECT_NAME
                : projectId;

        DocumentDocument doc = documentService.create(
                tenantId, project, targetPath,
                /*title*/ null, /*tags*/ null, mime,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                /*createdBy*/ userId,
                de.mhus.vance.shared.permission.WriteActor.user(subject));

        log.info("Template '{}' applied tenant='{}' project='{}' path='{}'",
                template.name(), tenantId, project, doc.getPath());
        return new AppliedTemplate(
                doc.getPath(),
                doc.getMimeType() != null ? doc.getMimeType() : mime);
    }

    /**
     * Scaffolds an application instead of writing a document: the form values
     * become the {@code create()} params and the application writes its own
     * manifest plus whatever derived artefacts belong to it.
     *
     * <p>Two things are deliberately kept on this side of the call rather than
     * delegated:
     *
     * <ul>
     *   <li><b>The permission check.</b> {@code CreateContext} carries only a
     *       nullable {@code userId}, and the applications resolve their write
     *       actor from it — a blank one (a service-account subject, for which
     *       {@code userId} is null by the rule above) would become
     *       {@code SecurityContext.SYSTEM} and skip the check entirely. So the
     *       call site enforces {@code CREATE} on the manifest with the
     *       authenticated subject before dispatching, the same way
     *       {@code ForeignAccessSupport} enforces per target.</li>
     *   <li><b>The exists check.</b> Every application raises its own
     *       {@code ToolException} for an occupied manifest; checking here keeps
     *       the REST contract at one HTTP 409 regardless of which app answered.</li>
     * </ul>
     */
    private AppliedTemplate applyApp(
            ResolvedTemplate template,
            String callerFolder,
            Map<String, Object> values,
            String tenantId,
            @Nullable String projectId,
            de.mhus.vance.shared.permission.SecurityContext subject) {

        String app = template.app();
        // A declared folder wins over the caller's, exactly as on the document path.
        String targetFolder = normalizeFolder(
                template.folder() != null ? template.folder() : callerFolder);
        if (targetFolder.isEmpty()) {
            // The app folder *is* the app's identity — a manifest at the project
            // root would turn the whole project into one app.
            throw new IllegalStateException("a target folder is required for an application template");
        }

        String project = (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.TENANT_PROJECT_NAME
                : projectId;
        String manifestPath = targetFolder + "/" + VanceApplication.APP_MANIFEST;

        permissionService.enforce(
                subject, new Resource.Document(tenantId, project, manifestPath), Action.CREATE);

        if (documentService.findByPath(tenantId, project, manifestPath).isPresent()) {
            throw new DocumentService.DocumentAlreadyExistsException(
                    "Document already exists: " + manifestPath);
        }

        String userId = subject.subjectType() == de.mhus.vance.shared.permission.SubjectType.USER
                ? subject.subjectId()
                : null;

        VanceApplication.CreateResult result = applicationRegistry.require(app).create(
                new VanceApplication.CreateContext(
                        tenantId, project, targetFolder, userId,
                        /*processId*/ null, /*overwrite*/ false, values));

        log.info("Template '{}' scaffolded app='{}' tenant='{}' project='{}' folder='{}' artefacts={}",
                template.name(), app, tenantId, project, targetFolder, result.artefacts().size());

        return new AppliedTemplate(
                result.manifestPath(),
                DocumentService.mimeFromPath(VanceApplication.APP_MANIFEST));
    }

    /**
     * Re-types the submitted values along the form's declared field types.
     * The web form seeds and submits every value as a string (including
     * {@code defaultValue}, which is a string in the DTO), while an
     * application reads its params with {@code instanceof Number} /
     * {@code instanceof Boolean} — an un-coerced {@code "10"} would silently
     * fall back to the app's own default instead of being honoured.
     *
     * <p>{@link FormValidator} has already established that the value parses;
     * this only carries that knowledge into the map. Unparsable or absent
     * values are left untouched — validation, not coercion, is what reports them.
     */
    private static Map<String, Object> coerceByFieldType(
            List<FormFieldDto> fields, Map<String, Object> values) {
        if (fields.isEmpty() || values.isEmpty()) {
            return values;
        }
        Map<String, Object> out = new LinkedHashMap<>(values);
        for (FormFieldDto field : fields) {
            Object raw = out.get(field.getName());
            if (!(raw instanceof String s) || s.isBlank()) continue;
            String type = field.getType() == null ? "" : field.getType();
            switch (type) {
                case FormValidator.TYPE_INTEGER -> {
                    try {
                        out.put(field.getName(), Integer.valueOf(s.trim()));
                    } catch (NumberFormatException ignored) {
                        // Left as the string it was: the validator reports it.
                    }
                }
                case FormValidator.TYPE_BOOLEAN -> {
                    String v = s.trim().toLowerCase(java.util.Locale.ROOT);
                    if ("true".equals(v) || "false".equals(v)) {
                        out.put(field.getName(), Boolean.valueOf(v));
                    }
                }
                default -> { }
            }
        }
        return out;
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
            // Structured render: a template body is a document (YAML, markdown
            // with front-matter), so a swallowed newline after `{{ … }}` is a
            // syntax error rather than a whitespace quirk.
            String rendered = templateRenderer.renderStructured(template.bodyContent(), ctx);
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

    /** Strips leading/trailing slashes and whitespace; {@code ""} when nothing is left. */
    private static String normalizeFolder(@Nullable String folder) {
        return folder == null ? "" : folder.trim().replaceAll("^/+|/+$", "");
    }

    private static String joinPath(String folder, String filename) {
        String f = folder == null ? "" : folder.trim().replaceAll("^/+|/+$", "");
        String name = filename.replaceAll("^/+", "");
        return f.isEmpty() ? name : f + "/" + name;
    }
}
