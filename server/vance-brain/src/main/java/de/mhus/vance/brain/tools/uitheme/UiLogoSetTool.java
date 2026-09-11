package de.mhus.vance.brain.tools.uitheme;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code ui_logo_set} — set the tenant's web-UI header logo by copying
 * a prepared image document from the current project into
 * {@code _vance/config/logo.<ext>} in the {@code _tenant} project.
 * The extension is derived from the source's MIME type and must be
 * one the serving endpoint looks for (svg / png / webp / jpg) — the
 * write-side guard that mirrors the read-side priority. Any existing
 * {@code logo.*} candidate is moved to the trash first, so the swap
 * is deterministic and the old logo survives in the tenant's trash.
 *
 * <p>Same rationale as {@code UiCustomCssSetTool}: worker processes
 * cannot reach {@code _tenant} with generic content tools
 * (sub-process clamp + SYSTEM gate), and the binary copy has no
 * generic equivalent anyway — {@code doc_write} is text-only and
 * {@code foreign_doc_copy} both refuses the reserved
 * {@code _vance/…} namespace and copies through a UTF-8 text layer.
 * This tool reads the source bytes from storage and writes them as a
 * storage document; the caller decides nothing about the target.
 *
 * <p>Prepare the source in the current project: fetch it with
 * {@code doc_import_url}, then shape it with the {@code image_*}
 * family. A size cap keeps a careless upload from turning the header
 * of every tenant page into a multi-megabyte download.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UiLogoSetTool implements Tool {

    /** Upload cap — the logo renders at ~20px; 2 MB is already generous. */
    static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;

    private final DocumentService documentService;
    private final KindToolSupport support;

    @Override
    public String name() {
        return "ui_logo_set";
    }

    @Override
    public String description() {
        return "Set the tenant's web-UI header logo from a prepared image "
                + "document in the current project. Supported formats: SVG, "
                + "PNG, WebP, JPEG. Requires the invoking user to be "
                + "tenant-ADMIN. Square sources work best; the header box is "
                + "fixed at 20px with object-contain, so a 2x raster (48-64px) "
                + "or an SVG is the crisp choice. Any previous tenant logo is "
                + "moved to the trash.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Set<String> labels() {
        return Set.of("uitheme", "write");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "path",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Path of the source image document in the "
                                                        + "current project (e.g. 'assets/logo.png'). Prepare it "
                                                        + "first: doc_import_url fetches a URL, the image_* tools "
                                                        + "resize / crop / enhance."),
                                "title",
                                        Map.of(
                                                "type", "string",
                                                "description", "Optional title for the logo document.")),
                "required", List.of("path"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String sourcePath = KindToolSupport.requireString(params, "path");
        String title = KindToolSupport.paramString(params, "title");

        DocumentDocument source = documentService
                .findByPath(ctx.tenantId(), ctx.projectId(), sourcePath)
                .orElseThrow(() -> new ToolException(
                        "Source document '" + sourcePath + "' not found in project '" + ctx.projectId() + "'"));
        // READ on the source — we stream its bytes.
        support.enforceDocWrite(ctx, source, de.mhus.vance.shared.permission.Action.READ);

        String mimeType = source.getMimeType();
        String extension = TenantUiCustomization.LOGO_EXTENSION_BY_MIME.get(
                mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT));
        if (extension == null) {
            throw new ToolException("Source '" + sourcePath + "' has MIME type '" + mimeType
                    + "' — not a supported logo format (svg, png, webp, jpg). "
                    + "Convert or re-export the image first.");
        }
        if (source.getSize() > MAX_LOGO_BYTES) {
            throw new ToolException("Source '" + sourcePath + "' is " + source.getSize()
                    + " bytes — the tenant logo is served to every page load and must stay "
                    + "under " + MAX_LOGO_BYTES + " bytes. Shrink it with image_resize "
                    + "(the header renders at 20px, so 48-64px is plenty).");
        }

        String targetPath = TenantUiCustomization.LOGO_PATH_PREFIX + extension;
        // CREATE on the target: the permission resolver maps _tenant writes
        // to tenant-ADMIN, so a non-admin caller is refused here.
        support.enforceDocWrite(
                ctx, TenantUiCustomization.TENANT_PROJECT, targetPath, de.mhus.vance.shared.permission.Action.CREATE);

        // Trash every existing logo candidate — including a different
        // extension — so the serving priority can never resurrect an old
        // logo the operator believed replaced.
        List<String> replaced = new ArrayList<>();
        for (String candidateExt : TenantUiCustomization.LOGO_EXTENSION_BY_MIME.values()) {
            documentService
                    .findByPath(
                            ctx.tenantId(),
                            TenantUiCustomization.TENANT_PROJECT,
                            TenantUiCustomization.LOGO_PATH_PREFIX + candidateExt)
                    .ifPresent(old -> {
                        documentService.trash(old.getId(), support.writeActor(ctx, old.getPath()));
                        replaced.add(old.getPath());
                    });
        }

        DocumentDocument created;
        try (InputStream content = documentService.loadContent(source)) {
            created = documentService.create(
                    ctx.tenantId(),
                    TenantUiCustomization.TENANT_PROJECT,
                    targetPath,
                    title != null ? title : firstNonBlank(source.getTitle(), "Tenant logo"),
                    /*tags*/ null,
                    mimeType,
                    content,
                    ctx.userId(),
                    support.writeActor(ctx, targetPath));
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new ToolException("Could not read source image '" + sourcePath + "': " + e.getMessage(), e);
        }

        log.info(
                "Tenant logo set tenant='{}' from '{}/{}' to '{}/{}' replaced={}",
                ctx.tenantId(),
                ctx.projectId(),
                sourcePath,
                TenantUiCustomization.TENANT_PROJECT,
                targetPath,
                replaced);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", TenantUiCustomization.TENANT_PROJECT);
        out.put("path", created.getPath());
        out.put("mimeType", created.getMimeType());
        out.put("size", created.getSize());
        out.put("replaced", replaced);
        return out;
    }

    private static String firstNonBlank(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
