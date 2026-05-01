package de.mhus.vance.brain.tools.relations;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentRelation;
import de.mhus.vance.shared.document.DocumentRelationsService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Read-only access to the project's relations graph. Filters are optional
 * and combine with AND — no filters returns the whole graph (capped by
 * {@code limit}). The agent uses this to look up "what does doc X cite",
 * "what's connected to Y", or "show me everything tagged as
 * {@code extracted_from}".
 *
 * <p>Truth lives in the YAML files under {@code relations/*.yaml} (project)
 * and {@code _vance/relations/*.yaml} (tenant default); this tool surfaces
 * them through {@link DocumentRelationsService}, which already cascades
 * across both layers.
 */
@Component
@RequiredArgsConstructor
public class RelationsFindTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "source", Map.of(
                            "type", "string",
                            "description", "Match relations whose source-document path equals this value."),
                    "target", Map.of(
                            "type", "string",
                            "description", "Match relations whose target-document path equals this value."),
                    "type", Map.of(
                            "type", "string",
                            "description", "Match relations of this type (relates_to, cites, extracted_from, …)."),
                    "related", Map.of(
                            "type", "string",
                            "description",
                            "Convenience: match relations where the path is either source or target. "
                                    + "Use instead of source/target when the direction is irrelevant."),
                    "limit", Map.of(
                            "type", "integer",
                            "description", "Maximum rows to return. Default 50, max 500.")),
            "required", List.of());

    private final DocumentRelationsService relationsService;

    @Override
    public String name() {
        return "relations_find";
    }

    @Override
    public String description() {
        return "Look up document-to-document relations declared in the project's "
                + "relations/*.yaml files. Filter by source, target, type, or "
                + "related (source-or-target). All filters are optional and "
                + "combine with AND.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String projectId = ctx.projectId();
        if (projectId == null) {
            throw new ToolException("Relations tools require a project scope");
        }
        String source = stringParam(params, "source");
        String target = stringParam(params, "target");
        String type = stringParam(params, "type");
        String related = stringParam(params, "related");
        int limit = intParam(params, "limit", DEFAULT_LIMIT);
        if (limit <= 0) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;

        List<DocumentRelation> all = relationsService.listRelations(ctx.tenantId(), projectId);
        List<Map<String, Object>> rows = new ArrayList<>();
        int totalMatched = 0;
        for (DocumentRelation r : all) {
            if (!source.isEmpty() && !source.equals(r.getSource())) continue;
            if (!target.isEmpty() && !target.equals(r.getTarget())) continue;
            if (!type.isEmpty() && !type.equals(r.getType())) continue;
            if (!related.isEmpty()
                    && !related.equals(r.getSource())
                    && !related.equals(r.getTarget())) continue;
            totalMatched++;
            if (rows.size() < limit) {
                rows.add(toRow(r));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("relations", rows);
        out.put("count", rows.size());
        out.put("totalMatched", totalMatched);
        out.put("truncated", totalMatched > rows.size());
        return out;
    }

    private static Map<String, Object> toRow(DocumentRelation r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source", r.getSource());
        row.put("type", r.getType());
        row.put("target", r.getTarget());
        if (r.getNote() != null) row.put("note", r.getNote());
        if (r.getDefinedIn() != null) row.put("definedIn", r.getDefinedIn());
        return row;
    }

    private static String stringParam(Map<String, Object> params, String key) {
        if (params == null) return "";
        Object v = params.get(key);
        if (!(v instanceof String s)) return "";
        return s.trim();
    }

    private static int intParam(Map<String, Object> params, String key, int fallback) {
        if (params == null) return fallback;
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }
}
