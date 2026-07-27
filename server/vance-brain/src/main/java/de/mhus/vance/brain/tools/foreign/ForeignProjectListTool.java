package de.mhus.vance.brain.tools.foreign;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * List the regular projects in the caller's tenant that the caller may READ —
 * the entry point for cross-project work ("look into project X"). An optional
 * {@code query} filters by name/title substring; without it every readable
 * regular project is returned. SYSTEM projects ({@code _vance}, {@code _user_*})
 * are never listed.
 */
@Component
@RequiredArgsConstructor
public class ForeignProjectListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of("type", "string",
                            "description", "Optional case-insensitive substring filter on project "
                                    + "name / title. Omit to list all readable projects.")),
            "required", List.of());

    private final ForeignAccessSupport foreign;

    @Override public String name() { return "foreign_project_list"; }

    @Override public String description() {
        return "List other projects in your tenant that you may read, with name + title + group + "
                + "kind. Use before foreign_doc_list / foreign_doc_search / foreign_doc_read / "
                + "foreign_doc_copy to discover which projects exist. Optional `query` filters by "
                + "name/title. SYSTEM projects are excluded.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("read-only", "cross-project"); }
    @Override public String searchHint() { return "Find other projects in the tenant to read from"; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String query = KindToolSupport.paramString(params, "query");
        String needle = query == null ? null : query.toLowerCase(Locale.ROOT);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProjectDocument p : foreign.listReadable(ctx)) {
            if (p.getKind() == ProjectKind.SYSTEM) continue;
            if (needle != null && !matches(p, needle)) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", p.getName());
            if (p.getTitle() != null) r.put("title", p.getTitle());
            if (p.getProjectGroupId() != null) r.put("projectGroupId", p.getProjectGroupId());
            if (p.getKind() != null) r.put("kind", p.getKind().name());
            if (p.getStatus() != null) r.put("status", p.getStatus().name());
            rows.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", ctx.tenantId());
        out.put("count", rows.size());
        out.put("projects", rows);
        return out;
    }

    private static boolean matches(ProjectDocument p, String needle) {
        String name = p.getName();
        String title = p.getTitle();
        return (name != null && name.toLowerCase(Locale.ROOT).contains(needle))
                || (title != null && title.toLowerCase(Locale.ROOT).contains(needle));
    }
}
