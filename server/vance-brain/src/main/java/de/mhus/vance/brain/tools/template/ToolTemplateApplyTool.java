package de.mhus.vance.brain.tools.template;

import de.mhus.vance.api.kit.ToolTemplateCatalogEntry;
import de.mhus.vance.brain.kit.KitException;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.brain.kit.TemplateApplier;
import de.mhus.vance.shared.kit.catalog.ToolTemplateCatalogService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Applies a tool-template with the supplied inputs. The agent calls
 * this after {@link ToolTemplateDescribeTool} surfaced the schema and
 * the user provided the values.
 *
 * <p>Carries the {@code write} + {@code side-effect} labels — applying
 * a template writes documents (oauth-config, server-tool-config) and
 * settings (encrypted secrets). Conversational engines defer it to the
 * discovery block; recipes explicitly for tool-setup promote it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolTemplateApplyTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "required", List.of("name", "projectId", "inputs"),
            "properties", Map.of(
                    "name", Map.of("type", "string",
                            "description", "Template name from tool_template_list."),
                    "projectId", Map.of("type", "string",
                            "description", "Target project — usually the current project. "
                                    + "Tool documents land here; cascade into _tenant when "
                                    + "appropriate by passing '_tenant'."),
                    "inputs", Map.of("type", "object",
                            "description", "Field name → value. PASSWORD inputs in plaintext "
                                    + "(server encrypts before persistence). Get the expected "
                                    + "fields from tool_template_describe."),
                    "token", Map.of("type", "string",
                            "description", "Optional auth token if the kit's git source needs one.")));

    private final ToolTemplateCatalogService catalogService;
    private final KitService kitService;

    @Override public String name() { return "tool_template_apply"; }

    @Override public String description() {
        return "Apply a tool-template with user-supplied inputs. Validates the "
                + "inputs against the template's schema, substitutes "
                + "{{var:fieldName}} in the kit's documents, persists "
                + "documents + settings. PASSWORD inputs are stored encrypted "
                + "via SettingService — they never land inline in a YAML "
                + "document. Returns the post-install hook (e.g. 'now connect "
                + "Atlassian in Connected Accounts') for the agent to surface.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("tool-template", "write", "side-effect"); }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (params == null) params = Map.of();
        String templateName = stringOrThrow(params.get("name"), "name");
        String projectId = stringOrThrow(params.get("projectId"), "projectId");
        Object inputsRaw = params.get("inputs");
        if (!(inputsRaw instanceof Map<?, ?> inputsMap)) {
            throw new ToolException("'inputs' must be an object");
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : inputsMap.entrySet()) {
            if (e.getKey() == null) continue;
            inputs.put(String.valueOf(e.getKey()), serialiseValue(e.getValue()));
        }
        String token = stringOrNull(params.get("token"));

        ToolTemplateCatalogEntry entry = catalogService.findByName(ctx.tenantId(), templateName);
        if (entry == null) {
            throw new ToolException("Template '" + templateName
                    + "' is not in the tenant catalog.");
        }

        TemplateApplier.ApplyResult result;
        try {
            result = kitService.applyTemplate(
                    ctx.tenantId(), projectId, entry.getSource(),
                    inputs, token, ctx.userId());
        } catch (KitException e) {
            log.warn("apply template '{}' for project='{}' failed: {}",
                    templateName, projectId, e.getMessage());
            throw new ToolException("apply failed: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", true);
        out.put("templateName", result.templateName());
        if (result.installer() != null) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("documentsAdded", sizeOf(result.installer().getDocumentsAdded()));
            stats.put("documentsUpdated", sizeOf(result.installer().getDocumentsUpdated()));
            stats.put("settingsAdded", sizeOf(result.installer().getSettingsAdded()));
            stats.put("settingsUpdated", sizeOf(result.installer().getSettingsUpdated()));
            out.put("installer", stats);
        }
        if (result.postInstall() != null) {
            Map<String, Object> pi = new LinkedHashMap<>();
            pi.put("kind", result.postInstall().kind().name().toLowerCase().replace('_', '-'));
            if (result.postInstall().provider() != null) {
                pi.put("provider", result.postInstall().provider());
            }
            if (result.postInstall().message() != null) {
                pi.put("message", result.postInstall().message());
            }
            out.put("postInstall", pi);
        }
        return out;
    }

    private static int sizeOf(@Nullable List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static String stringOrThrow(Object v, String field) {
        if (!(v instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + field + "' is required");
        }
        return s.trim();
    }

    private static @Nullable String stringOrNull(Object v) {
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    /**
     * Coerce a JSON-typed value to the {@code Map<String,String>} shape
     * the applier expects. Strings pass through; lists are JSON-encoded
     * so multi-select inputs can be parsed back into a string array; all
     * other scalars use {@link String#valueOf(Object)}.
     */
    private static String serialiseValue(@Nullable Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        if (v instanceof List<?>) {
            try {
                return TOOL_JSON.writeValueAsString(v);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new ToolException("could not encode list-valued input: " + e.getMessage(), e);
            }
        }
        return String.valueOf(v);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper TOOL_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
