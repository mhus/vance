package de.mhus.vance.brain.tools.starred;

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
 * Unstar a document.
 *
 * <p>Removes the entry — unless it carries something the person wrote (a
 * description, an emphasis, a hidden flag), in which case it is only switched
 * off. The distinction is not a detail: the alternative is that one mistaken call
 * silently deletes a typed note.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StarredRemoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("project", Map.of("type", "string",
                        "description", "Project of the starred document."));
                put("path", Map.of("type", "string",
                        "description", "Project-relative path of the starred document."));
            }},
            "required", List.of("project", "path"));

    private final StarredService starredService;
    private final StarredToolSupport support;

    @Override public String name() { return "starred_remove"; }

    @Override
    public String description() {
        return "Unstar a document. An entry carrying user-written fields (description, "
                + "highlight, hidden) is switched off rather than deleted, so nothing typed "
                + "is lost; re-starring brings it back with those fields intact.";
    }

    @Override public boolean primary() { return false; }

    @Override public boolean deferred() { return true; }

    @Override public Set<String> labels() {
        Set<String> labels = new java.util.HashSet<>(StarredToolSupport.BASE_LABELS);
        labels.add("write");
        return Set.copyOf(labels);
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String user = support.requireUser(ctx);
        String project = StarredToolSupport.requireParam(params, "project");
        String path = StarredToolSupport.requireParam(params, "path");

        boolean changed = starredService.unstar(
                ctx.tenantId(), user, project, path, support.subject(ctx));

        log.trace("StarredRemoveTool user='{}' project='{}' path='{}' changed={}",
                user, project, path, changed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changed", changed);
        result.put("message", changed
                ? "Unstarred " + project + "/" + path
                : "Not starred: " + project + "/" + path);
        return result;
    }
}
