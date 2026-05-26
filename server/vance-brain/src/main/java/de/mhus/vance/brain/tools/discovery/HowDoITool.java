package de.mhus.vance.brain.tools.discovery;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.discovery.DiscoveryResult;
import de.mhus.vance.brain.discovery.DiscoveryService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * The {@code how_do_i} tool — semantic discovery over capabilities
 * (manuals, skills, tools). Thin wrapper over
 * {@link DiscoveryService}; the heavy lifting (catalog render, LLM
 * call, schema validation) is delegated.
 *
 * <p>The {@link DiscoveryService} reference is {@code @Lazy} because
 * Spring builds a bean graph that would otherwise cycle here:
 * {@code DiscoveryService → SourceCatalogService → Builder →
 * List<Tool> → HowDoITool → DiscoveryService}. The lazy proxy is
 * resolved at first invocation, by which point all beans exist.
 *
 * <p>Returns one of three response shapes:
 *
 * <ul>
 *   <li>{@code loaded} — confident match, capability content is in
 *       the reply.</li>
 *   <li>{@code alternatives} — list of candidates; caller picks one
 *       and loads via {@code manual_read}.</li>
 *   <li>{@code hint} — no match or refinement needed.</li>
 * </ul>
 *
 * <p>See {@code specification/how-do-i.md} for the full design.
 */
@Component
@Slf4j
public class HowDoITool implements Tool {

    private static final int MAX_INTENT_LENGTH = 500;

    private final DiscoveryService discoveryService;

    public HowDoITool(@Lazy DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "intent", Map.of(
                            "type", "string",
                            "description",
                                    "One-sentence description of what you want "
                                            + "to do, in natural language. "
                                            + "Example: 'show the user a "
                                            + "picture from a web search result'.")),
            "required", List.of("intent"));

    @Override
    public String name() {
        return "how_do_i";
    }

    @Override
    public String description() {
        return "Semantic discovery across Vance's capabilities (manuals, "
                + "skills, tools). Pass a one-sentence intent and the tool "
                + "returns either the matching capability content "
                + "directly (`loaded`), a ranked list of candidates "
                + "(`alternatives`), or a hint when nothing matches "
                + "(`hint`). Call this BEFORE saying you cannot do "
                + "something — the system often knows more than your "
                + "training data does.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("how_do_i requires a tenant scope");
        }
        Object raw = params == null ? null : params.get("intent");
        if (!(raw instanceof String intent) || intent.isBlank()) {
            throw new ToolException("'intent' is required");
        }
        if (intent.length() > MAX_INTENT_LENGTH) {
            throw new ToolException("'intent' must be at most "
                    + MAX_INTENT_LENGTH + " characters (got "
                    + intent.length() + ")");
        }

        DiscoveryResult result;
        try {
            result = discoveryService.discover(
                    intent, ctx.tenantId(), ctx.projectId(), ctx.processId());
        } catch (LightLlmException e) {
            // Surface light-LLM errors as a tool exception so the
            // caller can fall back to manual_list / manual_read.
            throw new ToolException("how_do_i failed: " + e.getMessage());
        }

        return toResponse(result);
    }

    private static Map<String, Object> toResponse(DiscoveryResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", result.getIntent());
        if (result.getLoaded() != null) {
            out.put("loaded", matchToMap(result.getLoaded(), true));
        } else {
            out.put("loaded", null);
        }
        List<Map<String, Object>> alternatives = new ArrayList<>();
        if (result.getAlternatives() != null) {
            for (DiscoveryResult.Match m : result.getAlternatives()) {
                alternatives.add(matchToMap(m, false));
            }
        }
        out.put("alternatives", alternatives);
        out.put("hint", result.getHint());
        return out;
    }

    private static Map<String, Object> matchToMap(
            DiscoveryResult.Match m, boolean includeContent) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", m.getType());
        out.put("name", m.getName());
        if (m.getSource() != null) out.put("source", m.getSource());
        if (includeContent && m.getContent() != null) {
            out.put("content", m.getContent());
        }
        if (m.getSummary() != null) out.put("summary", m.getSummary());
        if (m.getScore() != null) out.put("score", m.getScore());
        return out;
    }
}
