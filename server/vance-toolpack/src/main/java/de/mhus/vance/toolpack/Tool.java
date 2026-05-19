package de.mhus.vance.toolpack;

import de.mhus.vance.api.tools.ToolSpec;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Runtime counterpart to {@link ToolSpec}: same metadata plus
 * {@link #invoke}. Lives in vance-toolpack so both vance-brain (server
 * dispatcher beans) and vance-foot (client-pushed tools) can implement
 * the same contract.
 *
 * <p>Implementations are stateless — the dispatcher calls them
 * concurrently from different scopes. All per-call state lives in the
 * {@link ToolInvocationContext} argument.
 *
 * <p>Return values are plain maps rather than strongly typed results so
 * the dispatcher can serialise them straight into the LLM tool-result
 * channel without a per-tool adapter.
 */
public interface Tool {

    /** Stable unique name — the identifier the LLM uses to call it. */
    String name();

    /** Short human-readable purpose, shown to the LLM. */
    String description();

    /**
     * {@code true} — advertised to the LLM on every turn.
     * {@code false} — only discoverable via {@code find_tools}.
     */
    boolean primary();

    /**
     * JSON-Schema object describing invocation parameters. Keep the
     * schema small and object-shaped ({@code type: "object"}, {@code
     * properties}, {@code required}) — richer constructs are translated
     * best-effort by downstream adapters.
     */
    Map<String, Object> paramsSchema();

    /** Executes the tool. Throws {@link ToolException} on failure. */
    Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx);

    /**
     * Variant for tools that want to invoke sibling tools through the
     * same allow-filter and listener path the engine uses. Default
     * forwards to the 2-arg form, so existing tools are unaffected.
     * Override only if the tool actually needs the bound surface
     * (e.g. server-side ScriptExecutor).
     *
     * <p>{@code bus} may be {@link ToolBus#NOOP} when no sibling-call
     * surface is bound (typical foot-side scenario).
     */
    default Map<String, Object> invoke(
            Map<String, Object> params,
            ToolInvocationContext ctx,
            ToolBus bus) {
        return invoke(params, ctx);
    }

    /**
     * Selector tags. Recipes can reference tools collectively via
     * {@code @<label>}. Built-in beans return an empty set by default;
     * configured tools override with the values from
     * {@code ServerToolDocument#labels}.
     *
     * <p>Convention used across the engine layer:
     * <ul>
     *   <li>{@code read-only} — pure lookup; no state mutation, no
     *       external side-effect. Safe in Plan-Mode exploration.</li>
     *   <li>{@code write} — mutates application state (documents,
     *       scratchpads, RAG-collections, project records).</li>
     *   <li>{@code executive} — orchestration / process-control
     *       (process_create, process_steer, recipe_apply, peer_notify).
     *       Mode filters strip these in EXPLORING/PLANNING.</li>
     *   <li>{@code side-effect} — observable mutation outside the
     *       Vance environment (web_fetch, exec_run, kit_apply).</li>
     * </ul>
     */
    default Set<String> labels() {
        return Set.of();
    }

    /**
     * Connection-profile gate. Returns the set of {@code profile} values
     * that may invoke this tool — {@code null} or empty means
     * unrestricted (every profile may use it). The default is
     * unrestricted; tools that depend on resources only available to a
     * specific profile (e.g. client-side filesystem tools that need a
     * direct user-WS) override this.
     *
     * <p>Profile values mirror the connection-handshake field
     * {@code profile} (see {@code engine-message-routing.md} §4.1.1).
     * Initial values: {@code "user"}, {@code "mobile"}, {@code "eddie"}.
     *
     * <p>Filtering happens in
     * {@code ContextToolsApi.classify} before the
     * Add/Remove/Defer overlays — tools whose {@code allowedForProfile}
     * does not contain the active profile drop out of the dispatch pool
     * for that turn entirely.
     */
    default Set<String> allowedForProfile() {
        return Set.of();
    }

    /**
     * Pack-level prompt fragment that engines inject into the system
     * message when this tool is reachable for the turn. Multi-tool packs
     * (REST, MCP) share one hint across all sub-tools and the engine
     * deduplicates — the goal is one short note per pack ("cloudId is
     * auto-injected for the Jira pack, call {@code find_tools(query='jira')}
     * to enumerate") rather than per sub-tool.
     *
     * <p>Empty string (the default) means "no extra hint" — the engine
     * skips the hint block entirely when no active tool carries one.
     */
    default String promptHint() {
        return "";
    }

    /**
     * If {@code true}, the tool is held back from the default LLM
     * tool-manifest. The discovery block in the system prompt advertises
     * it by name and {@link #searchHint()} only; the LLM activates it
     * by calling {@code describe_tool(name)} (see specification
     * {@code planning/tool-schema-deferral.md}).
     *
     * <p>Default {@code false}: tool ships in every turn's tool list as
     * <i>primary</i>. Recipes can override per-process via
     * {@code allowedToolsDefer} / {@code allowedToolsAdd}.
     */
    default boolean deferred() {
        return false;
    }

    /**
     * 5–15-word relevance hint surfaced in the discovery block when
     * {@link #deferred()} is {@code true}. Should give the LLM enough
     * signal to know when calling {@code describe_tool} is worthwhile.
     * Empty string for non-deferred tools.
     */
    default String searchHint() {
        return "";
    }

    /**
     * Default projection to the wire-format DTO. Overriding is rarely
     * useful — the wire contract matches this interface one-to-one.
     */
    default ToolSpec toSpec(String sourceId) {
        return ToolSpec.builder()
                .name(name())
                .description(description())
                .primary(primary())
                .source(sourceId)
                .paramsSchema(new LinkedHashMap<>(paramsSchema()))
                .labels(new LinkedHashSet<>(labels()))
                .allowedProfiles(new LinkedHashSet<>(allowedForProfile()))
                .deferred(deferred())
                .searchHint(searchHint())
                .build();
    }
}
