package de.mhus.vance.brain.tools.template;

import de.mhus.vance.api.kit.ToolTemplateCatalogEntry;
import de.mhus.vance.api.kit.ToolTemplateDescriptorDto;
import de.mhus.vance.api.kit.ToolTemplateInputDto;
import de.mhus.vance.brain.kit.KitException;
import de.mhus.vance.brain.kit.TemplateDescribeService;
import de.mhus.vance.shared.kit.catalog.ToolTemplateCatalogService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Resolves one tool-template — clones the kit, parses its
 * {@code template.yaml}, returns the input schema so the agent can
 * prompt the user field-by-field.
 *
 * <p>Typical flow:
 * <pre>
 *   user says "richte mir IMAP für Zoho ein"
 *   → tool_template_list             → finds 'zoho-imap'
 *   → tool_template_describe(zoho-imap) → inputs: [host?, user, password, …]
 *   → ASK_USER for each missing required input
 *   → tool_template_apply(zoho-imap, {...})
 * </pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolTemplateDescribeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "required", List.of("name"),
            "properties", Map.of(
                    "name", Map.of("type", "string",
                            "description", "Template name from tool_template_list.")));

    private final ToolTemplateCatalogService catalogService;
    private final TemplateDescribeService describeService;

    @Override public String name() { return "tool_template_describe"; }

    @Override public String description() {
        return "Resolve one tool-template by name and return its input schema "
                + "(name, type, label, help, required, default, choices, target) "
                + "plus the post-install hook (e.g. 'oauth-connect → atlassian'). "
                + "Use this to know which inputs to ask the user about before "
                + "calling tool_template_apply.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("tool-template", "read-only"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (params == null) params = Map.of();
        String name = stringOrThrow(params.get("name"));
        ToolTemplateCatalogEntry entry = catalogService.findByName(ctx.tenantId(), name);
        if (entry == null) {
            throw new ToolException("Template '" + name
                    + "' is not in the tenant catalog. Use tool_template_list to discover.");
        }
        ToolTemplateDescriptorDto dto;
        try {
            dto = describeService.describe(entry.getSource(), null);
        } catch (KitException e) {
            log.warn("describe template '{}' failed: {}", name, e.getMessage());
            throw new ToolException("describe failed: " + e.getMessage(), e);
        }
        return toMap(dto);
    }

    private static Map<String, Object> toMap(ToolTemplateDescriptorDto d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", d.getName());
        if (!StringUtils.isBlank(d.getTitle())) out.put("title", d.getTitle());
        if (!StringUtils.isBlank(d.getDescription())) out.put("description", d.getDescription());
        List<Map<String, Object>> inputs = new ArrayList<>();
        if (d.getInputs() != null) {
            for (ToolTemplateInputDto i : d.getInputs()) inputs.add(inputToMap(i));
        }
        out.put("inputs", inputs);
        if (d.getPostInstall() != null) {
            Map<String, Object> pi = new LinkedHashMap<>();
            pi.put("kind", d.getPostInstall().getKind());
            if (!StringUtils.isBlank(d.getPostInstall().getProvider())) {
                pi.put("provider", d.getPostInstall().getProvider());
            }
            if (!StringUtils.isBlank(d.getPostInstall().getMessage())) {
                pi.put("message", d.getPostInstall().getMessage());
            }
            out.put("postInstall", pi);
        }
        return out;
    }

    private static Map<String, Object> inputToMap(ToolTemplateInputDto i) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", i.getName());
        row.put("type", i.getType());
        row.put("label", i.getLabel());
        if (!StringUtils.isBlank(i.getHelp())) row.put("help", i.getHelp());
        row.put("required", i.isRequired());
        if (!StringUtils.isBlank(i.getDefaultValue())) row.put("default", i.getDefaultValue());
        if (i.getChoices() != null && !i.getChoices().isEmpty()) row.put("choices", i.getChoices());
        row.put("target", i.getTarget());
        return row;
    }

    private static String stringOrThrow(Object v) {
        if (!(v instanceof String s) || s.isBlank()) {
            throw new ToolException("'name' is required");
        }
        return s.trim();
    }
}
