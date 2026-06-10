package de.mhus.vance.brain.tools.fenchurch;

import de.mhus.vance.brain.fenchurch.FenchurchStyleService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
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
 * The {@code image_style_prompt} tool — returns the merged style
 * prefix that {@code image_generate} will prepend to the next call's
 * prompt, plus an itemised breakdown of which scope contributed
 * what. Read-only debug + introspection tool; never modifies state.
 */
@Component
@RequiredArgsConstructor
public class ImageStylePromptTool implements Tool {

    private final FenchurchStyleService styleService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of());

    @Override public String name() { return "image_style_prompt"; }

    @Override
    public String description() {
        return "Inspect the effective Fenchurch style prefix for the current "
                + "call. Returns {merged, layers} where `merged` is the "
                + "comma-joined string the next `image_generate` call will "
                + "prepend to its prompt, and `layers` lists each "
                + "contributing scope (tenant, user, project, session) with "
                + "its raw value. Use this to debug surprise styles, or "
                + "before writing with `image_style_set` to see what's "
                + "already in effect.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("read-only"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_style_prompt requires a tenant scope");
        }
        List<FenchurchStyleService.Layer> layers = styleService.readLayers(
                ctx.tenantId(), ctx.userId(), ctx.projectId(), ctx.processId());
        String merged = styleService.composeMergedPrompt(layers);
        List<FenchurchStyleService.Layer> effective =
                FenchurchStyleService.applyNoneCutoff(layers);

        List<Map<String, Object>> layerView = new ArrayList<>(effective.size());
        for (FenchurchStyleService.Layer l : effective) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("scope", l.scope().name().toLowerCase(Locale.ROOT));
            entry.put("prefix", l.prefix());
            layerView.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merged", merged);
        out.put("layers", layerView);
        return out;
    }
}
