package de.mhus.vance.addon.brain.centauri.tool;

import de.mhus.vance.addon.brain.centauri.FeedsApplication;
import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * One-shot bootstrap of an {@code app: feeds} folder.
 *
 * <p>Exists so no agent hand-writes the manifest. The shape is small but easy to
 * get wrong in exactly two ways an LLM tends to get wrong: the config block sits
 * under {@code feeds:} rather than at the manifest root, and {@code streams} is a
 * list of {@code {source, selector}} objects rather than bare source names. Both
 * produce a manifest that parses and then shows an empty feed.
 *
 * <p>The tool does <b>not</b> configure sources. Which endpoints exist is
 * operator configuration ({@code centauri.endpoint.<id>.*}) that no agent can
 * write — see the {@code feeds-sources} manual.
 */
@Component
@Slf4j
public class FeedsAppCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Target folder (e.g. 'apps/morning'). "
                                + "_app.yaml is written inside it."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("streams", Map.of("type", "array",
                        "description", "Streams to read, each "
                                + "`{ source: '<endpoint id>', selector?: '<selector>' }`. "
                                + "A bare string is accepted and means that source's default "
                                + "stream. Source ids come from the configured endpoints — call "
                                + "no source you have not seen in the project's settings. "
                                + "May be empty; streams can be added later in the feed's "
                                + "configuration tab.",
                        "items", Map.of("type", "object")));
                put("since", Map.of("type", "string",
                        "description", "Time window, relative: '-7d', '-12h', '-30m'. "
                                + "Relative on purpose — a fixed date quietly stops matching "
                                + "as it ages."));
                put("pageSize", Map.of("type", "integer",
                        "description", "Entries per page. Default 20."));
                put("overwrite", Map.of("type", "boolean",
                        "description", "Replace an existing manifest. Default false."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final FeedsApplication application;

    public FeedsAppCreateTool(EddieContext eddieContext, FeedsApplication application) {
        this.eddieContext = eddieContext;
        this.application = application;
    }

    @Override public String name() { return "feeds_app_create"; }

    @Override
    public String description() {
        return "Create a feed — a folder app that reads foreign time-ordered streams "
                + "(news, wiki changes, earthquakes) as one endless scroll, merged "
                + "chronologically and filtered. Use this instead of hand-writing "
                + "`_app.yaml`. Sources themselves are operator settings and cannot be "
                + "created here.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "feeds");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object rawFolder = params.get("folder");
        String folder = rawFolder instanceof String s && !s.isBlank() ? s.trim() : null;
        if (folder == null) {
            throw new ToolException("folder is required");
        }
        boolean overwrite = Boolean.TRUE.equals(params.get("overwrite"));

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        Map<String, Object> appParams = new LinkedHashMap<>(params);
        appParams.remove("folder");
        appParams.remove("overwrite");
        appParams.remove("projectId");

        VanceApplication.CreateContext cc = new VanceApplication.CreateContext(
                ctx.tenantId(), project.getName(), folder,
                ctx.userId(), ctx.processId(), overwrite, appParams);
        VanceApplication.CreateResult result = application.create(cc);

        log.info("FeedsAppCreateTool folder='{}' project='{}'", folder, project.getName());
        return result.toMap();
    }
}
