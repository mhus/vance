package de.mhus.vance.brain.tools.uitheme;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.brain.tools.report.CssSanitizer;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code ui_custom_css_set} — set or replace the tenant-wide custom
 * stylesheet ({@code _vance/config/custom.css} in the {@code _tenant}
 * project) that restyles the web UI for every user of the tenant.
 * Write access is the invoking user's to have: the permission resolver
 * grants {@code _tenant} writes to tenant-ADMINs only, so a non-admin
 * caller gets a refusal to pass on to the operator.
 *
 * <p><b>Why a named tool instead of {@code doc_write} with
 * {@code projectId}.</b> Worker processes (the creator) are
 * sub-processes: {@code EddieContext.resolveProject} clamps their
 * {@code projectId} to the inherited project, so no generic content
 * tool can reach the {@code _tenant} project from a worker. This tool
 * carries the fixed target itself — no caller-controllable project at
 * all — and adds the two things a generic write cannot: the content
 * runs through {@link CssSanitizer} <b>at write time</b> (the browser
 * is a real resource loader; an {@code @import} or external
 * {@code url()} would fire requests from every tenant browser), and
 * the response reports whether anything was stripped so the creator
 * can warn the operator instead of wondering later why a rule does
 * nothing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UiCustomCssSetTool implements Tool {

    private final DocumentService documentService;
    private final KindToolSupport support;

    @Override
    public String name() {
        return "ui_custom_css_set";
    }

    @Override
    public String description() {
        return "Set or replace the tenant-wide custom stylesheet that "
                + "restyles the web UI for every user of the tenant. Requires "
                + "the invoking user to be tenant-ADMIN. External references "
                + "(@import, url() with non-data sources) are stripped before "
                + "storing — the response reports whether that happened. Read "
                + "the current state first with ui_custom_css_get so an "
                + "existing customization is extended, not silently replaced.";
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
                                "content",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "The full CSS body. Replaces "
                                                + "the previous stylesheet entirely — "
                                                + "fetch the current one with "
                                                + "ui_custom_css_get first when a change "
                                                + "should build on it.")),
                "required", List.of("content"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String content = KindToolSupport.requireString(params, "content");
        support.enforceDocWrite(
                ctx,
                TenantUiCustomization.TENANT_PROJECT,
                TenantUiCustomization.CUSTOM_CSS_PATH,
                de.mhus.vance.shared.permission.Action.CREATE);

        // Sanitize at write time — the same filter the serving endpoint
        // applies. Write-time matters: the operator gets told what was
        // stripped (the response flag) instead of a silent rule that does
        // nothing, and the stored document is itself clean.
        String sanitized = CssSanitizer.sanitize(content);
        boolean stripped = !sanitized.equals(content);
        if (stripped) {
            log.warn("ui_custom_css_set stripped constructs from the submitted "
                    + "tenant stylesheet — see the CssSanitizer log for details");
        }

        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), TenantUiCustomization.TENANT_PROJECT, TenantUiCustomization.CUSTOM_CSS_PATH);

        DocumentDocument result;
        boolean overwritten;
        if (existing.isPresent()) {
            result = documentService.update(
                    existing.get().getId(),
                    /*title*/ null,
                    /*tags*/ null,
                    sanitized,
                    /*newPath*/ null,
                    /*autoSummary*/ null,
                    /*summaryDirty*/ null,
                    /*ragEnabled*/ null,
                    "text/css",
                    DocumentService.TOOL_IDENTITY,
                    support.writeActor(ctx, TenantUiCustomization.CUSTOM_CSS_PATH));
            overwritten = true;
        } else {
            try {
                result = documentService.create(
                        ctx.tenantId(),
                        TenantUiCustomization.TENANT_PROJECT,
                        TenantUiCustomization.CUSTOM_CSS_PATH,
                        "Tenant custom stylesheet",
                        /*tags*/ null,
                        "text/css",
                        new ByteArrayInputStream(sanitized.getBytes(StandardCharsets.UTF_8)),
                        ctx.userId(),
                        support.writeActor(ctx, TenantUiCustomization.CUSTOM_CSS_PATH));
            } catch (DocumentService.DocumentAlreadyExistsException e) {
                throw new ToolException(e.getMessage(), e);
            }
            overwritten = false;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", TenantUiCustomization.TENANT_PROJECT);
        out.put("path", TenantUiCustomization.CUSTOM_CSS_PATH);
        out.put("id", result.getId());
        out.put("overwritten", overwritten);
        out.put("sanitized", stripped);
        if (stripped) {
            out.put(
                    "note",
                    "The submitted CSS contained constructs the "
                            + "stylesheet sanitizer removes (external @import / url() "
                            + "references, script vectors). They were stripped before "
                            + "storing — tell the operator which rules were dropped.");
        }
        return out;
    }
}
