package de.mhus.vance.api.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Wire-format description of a tool.
 *
 * <p>Used in both directions: the server advertises its tools to clients
 * (and meta-tools to the LLM), and clients push their own tools to the
 * server on connect. Deliberately flat JSON with no behaviour — the
 * runtime counterpart lives in {@code de.mhus.vance.brain.tools.Tool}.
 *
 * <p>{@link #paramsSchema} is a JSON-Schema subset (object schemas with
 * named properties and a {@code required} list) — enough to translate to
 * langchain4j's parameter format without pulling the full JSON-Schema
 * dependency into the API contract.
 *
 * <p>{@link #primary} controls visibility to the LLM. Primary tools are
 * listed up-front in every turn; secondary tools are only shown when the
 * LLM asks via the {@code find_tools} meta-tool.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class ToolSpec {

    /** Stable unique name — the identifier the LLM uses to call the tool. */
    private String name;

    /** Short human-readable purpose, shown to the LLM. */
    private String description;

    /**
     * {@code true} — tool is advertised to the LLM on every turn.
     * {@code false} — tool is only discoverable via {@code find_tools}.
     */
    private boolean primary;

    /**
     * Origin of the tool — {@code "server"}, {@code "client"}, or a plugin
     * identifier. Informational; dispatching goes through the tool's
     * {@code ToolSource}, not this field.
     */
    private @Nullable String source;

    /** JSON-Schema object describing the tool's invocation parameters. */
    @Builder.Default
    private Map<String, Object> paramsSchema = new LinkedHashMap<>();

    /**
     * Selector tags. Mirror of {@code Tool.labels()} on the server side
     * — lets client-pushed tools join the same selector pool as
     * server-managed tools (recipe {@code @<label>}-references and
     * Plan-Mode's {@code read-only} filter alike).
     *
     * <p>Convention: tools that don't mutate state carry the
     * {@code "read-only"} label; mutation tools carry {@code "write"};
     * orchestration / process-control tools carry {@code "executive"};
     * tools with externally observable side-effects (shell, web,
     * kit-apply) carry {@code "side-effect"}. Mode filters
     * (Arthur's EXPLORING/PLANNING) and recipes use these via
     * {@code @<label>}-selectors. See {@code planning/tool-schema-deferral.md} §5.
     */
    @Builder.Default
    private Set<String> labels = new LinkedHashSet<>();

    /**
     * Wire-mirror of {@code Tool.allowedForProfile()}. Set of connection-
     * profile names the tool is restricted to. Empty/null = unrestricted
     * (every profile may invoke it).
     *
     * <p>Used by client-pushed tools (foot side) to declare that they
     * only work for direct {@code user} or {@code mobile} connections —
     * not for {@code eddie}-profile hub clients, which cannot route
     * client-side tool results back to the originating user-WS. See
     * {@code eddie-engine.md} §8.4 + {@code engine-message-routing.md}
     * §4.1.1.
     */
    @Builder.Default
    private Set<String> allowedProfiles = new LinkedHashSet<>();

    /**
     * Wire-mirror of {@code Tool.deferred()}. {@code true} means the
     * tool is held back from the default LLM tool-manifest and only
     * surfaces through the discovery-block + {@code describe_tool}
     * activation. See {@code planning/tool-schema-deferral.md} §4.
     */
    @Builder.Default
    private boolean deferred = false;

    /**
     * Wire-mirror of {@code Tool.searchHint()}. 5–15-word relevance
     * hint shown in the discovery block when {@link #deferred} is
     * {@code true}. Empty for non-deferred tools.
     */
    @Builder.Default
    private String searchHint = "";

    /**
     * Wire-mirror of {@code Tool.safety()}. {@code SAFE_PROBE} when the
     * tool performs lookup-only / non-mutating work; {@code MUTATING}
     * otherwise (default). Used by diagnostic / service engines (Agrajag)
     * that may only invoke {@code SAFE_PROBE} tools during a turn.
     */
    @Builder.Default
    private ToolSafety safety = ToolSafety.MUTATING;

    /**
     * Wire-mirror of {@code Tool.requiresEngineRoles()}. When non-empty,
     * the manifest builder hides the tool from engines whose declared
     * {@code roles()} don't carry every role in this set. Default empty
     * means "any engine may see this tool".
     *
     * <p>Used for the tool-health-writer / tool-prober / repair-actor
     * audience gates introduced with Agrajag. See
     * {@code specification/agrajag-engine.md} §8.
     */
    @Builder.Default
    private Set<String> requiresEngineRoles = new LinkedHashSet<>();
}
