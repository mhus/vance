package de.mhus.vance.brain.tools.uitheme;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code ui_custom_css_get} — read the tenant-wide custom stylesheet
 * that the web UI serves to every user of the tenant. Read access is
 * every tenant member's (the permission resolver grants READ on the
 * {@code _tenant} project to all members — the stylesheet is a UI
 * asset, and hiding it from the people it styles would only make
 * debugging harder). Returns the current CSS body, empty when none is
 * set.
 *
 * <p>Counterpart of {@code GET /brain/{tenant}/ui/custom-css}, with one
 * deliberate difference: the tool returns the <b>stored</b> document
 * as-is, while the controller sanitises on every serve. For content
 * written through {@code ui_custom_css_set} the two are identical (the
 * set tool sanitises at write time); for an operator hand-edit in the
 * content editor they can differ — the browser path strips external
 * references ({@code @import}, non-data {@code url()}) the tool still
 * shows. That is useful, not a bug: the creator needs the stored truth
 * to edit and extend, not the served subset. The response carries a
 * {@code note} reminding of the serve-time filter.
 */
@Component
@RequiredArgsConstructor
public class UiCustomCssGetTool implements Tool {

    private final DocumentService documentService;
    private final KindToolSupport support;

    @Override
    public String name() {
        return "ui_custom_css_get";
    }

    @Override
    public String description() {
        return "Read the tenant-wide custom stylesheet of the web UI. "
                + "Returns the current CSS body — empty when the tenant has "
                + "none. Readable by every tenant member.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Set<String> labels() {
        return Set.of("uitheme", "read");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        support.enforceDocWrite(
                ctx,
                TenantUiCustomization.TENANT_PROJECT,
                TenantUiCustomization.CUSTOM_CSS_PATH,
                de.mhus.vance.shared.permission.Action.READ);
        String css = documentService
                .lookupCascade(
                        ctx.tenantId(), TenantUiCustomization.TENANT_PROJECT, TenantUiCustomization.CUSTOM_CSS_PATH)
                .map(LookupResult::content)
                .orElse("");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", TenantUiCustomization.TENANT_PROJECT);
        out.put("path", TenantUiCustomization.CUSTOM_CSS_PATH);
        out.put("content", css);
        out.put(
                "note",
                "Content is the stored document as-is; the browser endpoint strips external"
                        + " references (@import, non-data url()) from what it actually serves.");
        return out;
    }
}
