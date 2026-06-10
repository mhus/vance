package de.mhus.vance.brain.tools.fenchurch;

import de.mhus.vance.brain.fenchurch.FenchurchStyleService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The {@code image_style_get} tool — returns the calling LLM's
 * <i>own</i> style layer (session by default). Sister tool
 * {@code image_style_prompt} surfaces the full merged cascade.
 *
 * <p>Keeping read-of-own-layer separate from read-of-merged makes
 * "what can I change" obvious for the LLM: this tool reports the
 * <em>writable</em> layer, the prompt tool reports the
 * <em>effective</em> prefix the next {@code image_generate} call will
 * see.
 */
@Component
@RequiredArgsConstructor
public class ImageStyleGetTool implements Tool {

    private final FenchurchStyleService styleService;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of());

    @Override public String name() { return "image_style_get"; }

    @Override
    public String description() {
        return "Read the calling LLM's own Fenchurch style layer (session "
                + "scope). Returns {scope, prefix} or {scope, prefix: null} "
                + "when nothing is set. Use this to check what YOU set; for "
                + "the full merged prefix that will actually be applied to "
                + "the next image, use `image_style_prompt`.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("read-only"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("image_style_get requires a tenant scope");
        }
        String prefix = styleService.readScope(
                ctx.tenantId(), FenchurchStyleService.Scope.SESSION,
                ctx.userId(), ctx.projectId(), ctx.processId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", FenchurchStyleService.Scope.SESSION.name().toLowerCase(Locale.ROOT));
        out.put("prefix", prefix);
        return out;
    }
}
