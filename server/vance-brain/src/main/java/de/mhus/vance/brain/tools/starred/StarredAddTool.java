package de.mhus.vance.brain.tools.starred;

import de.mhus.vance.shared.starred.StarredItem;
import de.mhus.vance.shared.starred.StarredService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Star a document, or edit an entry the user already has.
 *
 * <p>{@code kind} and {@code type} are deliberately <b>not</b> parameters: they
 * are read from the live document. A model asked for a type would supply a
 * plausible one, and a wrong type breaks a "send to" with nothing in the UI
 * saying so.
 *
 * <p>Omitted flags mean "leave as it is", so re-starring an existing entry never
 * destroys a description or an emphasis the person set.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StarredAddTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("project", Map.of("type", "string",
                        "description", "Project the document lives in."));
                put("path", Map.of("type", "string",
                        "description", "Project-relative document path. For an application, "
                                + "the manifest path (e.g. 'links/_app.yaml')."));
                put("title", Map.of("type", "string",
                        "description", "Label for the tile. Omit to take the document's own "
                                + "title, or to keep an existing one."));
                put("description", Map.of("type", "string",
                        "description", "Optional note shown under the title."));
                put("highlight", Map.of("type", "boolean",
                        "description", "Emphasise the tile. Visual only — it never "
                                + "influences which entry a lookup picks."));
                put("hidden", Map.of("type", "boolean",
                        "description", "Register it but keep it off the start page. Still "
                                + "found by a lookup."));
            }},
            "required", List.of("project", "path"));

    private final StarredService starredService;
    private final StarredToolSupport support;

    @Override public String name() { return "starred_add"; }

    @Override
    public String description() {
        return "Star a document for the user, or edit an entry they already have (title, "
                + "description, highlight, hidden). The document kind and app type are read "
                + "from the document itself and cannot be passed. Omitted fields keep their "
                + "current value.";
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

        try {
            StarredItem item = starredService.star(
                    ctx.tenantId(), user, project, path,
                    StarredToolSupport.paramString(params, "title"),
                    StarredToolSupport.paramString(params, "description"),
                    StarredToolSupport.paramBoolean(params, "highlight"),
                    StarredToolSupport.paramBoolean(params, "hidden"),
                    support.subject(ctx));

            log.trace("StarredAddTool user='{}' project='{}' path='{}'", user, project, path);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("starred", StarredToolSupport.row(item));
            return result;
        } catch (StarredService.StarredException e) {
            throw new ToolException(e.getMessage());
        }
    }
}
