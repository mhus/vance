package de.mhus.vance.brain.tools.starred;

import de.mhus.vance.shared.starred.StarredItem;
import de.mhus.vance.shared.starred.StarredService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Read the user's starred documents.
 *
 * <p>Returns registered entries — including the ones hidden from the start page,
 * because "hidden" means out of sight, not out of service, and the agent is the
 * technical consumer.
 *
 * <p>Strictly speaking this tool is nearly redundant: the control file <em>is</em>
 * the answer and an agent could read it with {@code doc_read}. It exists because
 * it hides the hub-project resolution ({@code _user_<login>}) — that is not
 * something a model should be assembling by hand.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StarredListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("type", Map.of("type", "string",
                        "description", "Only entries of this app type (e.g. 'links')."));
                put("kind", Map.of("type", "string",
                        "description", "Only entries of this document kind (e.g. 'workpage')."));
            }},
            "required", List.of());

    private final StarredService starredService;
    private final StarredToolSupport support;

    @Override public String name() { return "starred_list"; }

    @Override
    public String description() {
        return "List the documents the user starred: project, path, kind, app type, title. "
                + "Includes entries hidden from the start page — those are still valid "
                + "targets. Filter with 'type' for an app capability (which app takes a "
                + "link?) or 'kind' for a document form.";
    }

    @Override public boolean primary() { return false; }

    @Override public boolean deferred() { return true; }

    @Override public Set<String> labels() {
        Set<String> labels = new java.util.HashSet<>(StarredToolSupport.BASE_LABELS);
        labels.add("read");
        return Set.copyOf(labels);
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String user = support.requireUser(ctx);
        String type = StarredToolSupport.paramString(params, "type");
        String kind = StarredToolSupport.paramString(params, "kind");

        List<StarredItem> items;
        if (type != null) {
            items = starredService.listByType(ctx.tenantId(), user, type);
        } else if (kind != null) {
            items = starredService.listByKind(ctx.tenantId(), user, kind);
        } else {
            items = starredService.listResolvable(ctx.tenantId(), user);
        }

        log.trace("StarredListTool user='{}' type='{}' kind='{}' returned={}",
                user, type, kind, items.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", items.size());
        result.put("entries", items.stream().map(StarredToolSupport::row).toList());
        if (items.isEmpty()) {
            result.put("hint", "Nothing starred"
                    + (type != null ? " of type '" + type + "'" : "")
                    + (kind != null ? " of kind '" + kind + "'" : "")
                    + ". A star is set by the person, in the Cortex Actions menu.");
        }
        return result;
    }
}
