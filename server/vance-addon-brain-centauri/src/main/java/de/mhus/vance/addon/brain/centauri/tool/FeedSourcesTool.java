package de.mhus.vance.addon.brain.centauri.tool;

import de.mhus.vance.brain.centauri.FeedSourceFactory;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedScope;
import de.mhus.vance.toolpack.feed.FeedSelector;
import de.mhus.vance.toolpack.feed.FeedSelectorKind;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Which feed sources this project has, and which streams each offers.
 *
 * <p>The point of this tool is to make one specific failure impossible: an agent
 * guessing a source id. A guessed id produces a feed that opens empty and a note
 * the user has to decode, so being able to look the ids up is worth a tool.
 *
 * <p>It lists the <b>assembled</b> sources, not the raw settings — and it cannot
 * create or change one. Configuring an endpoint stays operator work; see the
 * {@code feeds-sources} manual for what to tell the user.
 */
@Component
@Slf4j
public class FeedSourcesTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of("type", "string",
                            "description", "Default: active project.")),
            "required", List.of());

    private final EddieContext eddieContext;
    private final FeedSourceFactory sourceFactory;

    public FeedSourcesTool(EddieContext eddieContext, FeedSourceFactory sourceFactory) {
        this.eddieContext = eddieContext;
        this.sourceFactory = sourceFactory;
    }

    @Override public String name() { return "feed_sources"; }

    @Override
    public String description() {
        return "List the feed sources configured in this project with the streams each "
                + "offers. Call this before feeds_app_create or feed_read when you do not "
                + "already know the source ids — never guess one. Cannot configure a "
                + "source; that is an operator setting.";
    }

    @Override public boolean primary() { return false; }

    /** Off the default manifest: relevant only when a feed is actually the topic. */
    @Override public boolean deferred() { return true; }

    @Override public String searchHint() {
        return "which feed/news sources exist, their stream selectors";
    }

    @Override public Set<String> labels() {
        return Set.of("eddie", "read-only", "feeds");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        FeedScope scope = new FeedScope(
                ctx.tenantId(), project.getName(), ctx.processId(), ctx.userId());

        List<Map<String, Object>> sources = new ArrayList<>();
        for (FeedSourceInstance instance : sourceFactory.assemble(scope)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", instance.id());
            entry.put("displayName", instance.displayName());
            try {
                FeedCapabilities caps = instance.capabilities();
                entry.put("selectorMode", caps.selectorMode().name());
                if (caps.selectorMode() == de.mhus.vance.toolpack.feed.FeedSelectorMode.FREEFORM) {
                    List<String> kinds = new ArrayList<>();
                    for (FeedSelectorKind kind : caps.selectorKinds()) {
                        kinds.add(kind.name().toLowerCase(java.util.Locale.ROOT));
                    }
                    entry.put("selectorKinds", kinds);
                } else {
                    List<String> selectors = new ArrayList<>();
                    for (FeedSelector selector : instance.listSelectors()) {
                        selectors.add(selector.value());
                    }
                    entry.put("selectors", selectors);
                }
            } catch (RuntimeException e) {
                // Reported, not omitted: a source missing from the list looks like
                // one that was never configured, which sends the user to the wrong
                // place to fix it.
                entry.put("error", String.valueOf(e.getMessage()));
            }
            sources.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.getName());
        out.put("sources", sources);
        if (sources.isEmpty()) {
            out.put("hint", "No sources configured in this project. Configuring one is an "
                    + "operator setting (centauri.endpoint.<id>.*) that no tool can write — "
                    + "run manual_read('feeds-sources') and tell the user what to set.");
        }
        log.debug("FeedSourcesTool project='{}' sources={}", project.getName(), sources.size());
        return out;
    }
}
