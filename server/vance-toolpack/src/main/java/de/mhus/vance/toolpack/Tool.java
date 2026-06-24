package de.mhus.vance.toolpack;

import de.mhus.vance.api.tools.ToolSafety;
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
     * Whether tool calls of this kind tend to produce assistant text
     * that's worth re-analysing for durable insights (Prak's job).
     *
     * <p>Default {@code true} — most tools do real work whose result the
     * assistant synthesises into something Prak should capture: REST/
     * IMAP/MCP packs that touch external resources (failures and
     * recoveries are insights), {@code doc_*} mutations (architectural
     * decisions), {@code exec_run} / {@code research_*} / {@code web_*}
     * (substantive findings), even read-style searches
     * ({@code doc_grep}, {@code file_grep}) where the assistant tends to
     * synthesise patterns.
     *
     * <p>Set to {@code false} only for purely mechanical tools whose
     * post-call text is structurally an acknowledgement: plan tracking
     * ({@code todo_*}), discovery / introspection ({@code find_tools},
     * {@code describe_tool}, {@code manual_*}, {@code how_do_i},
     * {@code recipe_describe}, {@code tool_result_read}), trivial
     * lookups ({@code current_time}, {@code whoami}), work-target meta
     * ({@code work_target_*}), and read-only listings whose output is
     * paths/names rather than content ({@code doc_list},
     * {@code doc_info}, {@code doc_note_list}, {@code file_list},
     * {@code file_count}, {@code process_list}, {@code process_status}).
     *
     * <p>Engines persisting an assistant message look this up across
     * every tool invoked in the turn; when <em>every</em> tool returns
     * {@code false} they stamp {@code meta["prakSkip"]=true} on the
     * message and Prak's listener skips the analyser entirely.
     */
    default boolean contributesPrak() {
        return true;
    }

    /**
     * Optional one-line recovery hint that {@code ToolDispatcher}
     * prepends to the error text when this tool throws
     * {@link ToolException}. Evergreen prose — not workflow-deep like a
     * manual, just the typical fix path so the LLM (and the user
     * reading the error) doesn't burn a turn re-discovering the obvious.
     *
     * <p>Good examples:
     * <ul>
     *   <li>{@code imap_*}: "Check imap.host/user/password in project
     *       settings; auth expired? Re-link credential."</li>
     *   <li>{@code client_file_*}: "Requires CLIENT target — Foot must
     *       be connected. Use work_target_set(kind=WORK) for server."</li>
     *   <li>{@code exec_run}: "Non-zero exit = inspect stderr; timeout =
     *       retry with deadlineSeconds; missing binary = adjust PATH."</li>
     *   <li>{@code web_fetch}: "4xx = bad URL; 5xx = retry with backoff;
     *       timeout = remote slow, try smaller scope."</li>
     * </ul>
     *
     * <p>Default {@code null} — no hint, the bare error text reaches
     * the LLM. For deeper workflow-level guidance use a manual
     * ({@code manual_read('...')}) — this field is specifically the
     * one-liner that the assistant should see <em>without</em> having
     * to ask.
     */
    default @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return null;
    }

    /**
     * Auto-labels that Prak attaches to insights extracted from spans
     * involving this tool. Default empty. Adding domain labels at the
     * tool level makes downstream memory-search ("show me what we know
     * about IMAP failures") meaningfully faster without pushing
     * label-engineering onto every prompt.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code imap_*}: {@code {"email", "imap", "integration"}}</li>
     *   <li>{@code doc_create}/{@code doc_write}: {@code {"knowledge",
     *       "documents"}}</li>
     *   <li>{@code exec_run}: {@code {"execution", "shell"}}</li>
     * </ul>
     *
     * <p>Keep labels lower-case, kebab- or single-word. Prak unions
     * these across every tool used in the analysed span and merges with
     * any labels the analyser itself emits.
     */
    default java.util.Set<String> prakLabels() {
        return java.util.Set.of();
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
     * Whether the tool is non-mutating ({@link ToolSafety#SAFE_PROBE})
     * or mutates state somewhere ({@link ToolSafety#MUTATING}).
     * Diagnostic engines (Agrajag) restrict themselves to {@code SAFE_PROBE}
     * tools during probe turns so checking the world can never change it.
     *
     * <p>Default derives from {@link #labels()}: any tool that carries
     * the {@code "read-only"} label is automatically {@code SAFE_PROBE};
     * everything else stays {@code MUTATING}. Tools can override
     * explicitly when the label-derivation doesn't fit (e.g. lookup
     * tools whose labels haven't been ported yet, or write tools that
     * happen to be safe to retry).
     */
    default ToolSafety safety() {
        return labels().contains("read-only")
                ? ToolSafety.SAFE_PROBE
                : ToolSafety.MUTATING;
    }

    /**
     * When non-empty, the manifest builder hides the tool from engines
     * whose declared {@code ThinkEngine.roles()} don't carry every role
     * in this set. Default empty — any engine may see the tool.
     *
     * <p>The set is intersected with the engine's role set at classify
     * time. Used for the Agrajag-specific audience gates
     * ({@code tool-prober}, {@code tool-health-writer},
     * {@code tool-health-reader}); future service engines (Lunkwill,
     * Prak) reuse the same mechanism with their own role labels.
     */
    default Set<String> requiresEngineRoles() {
        return Set.of();
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
                .safety(safety())
                .requiresEngineRoles(new LinkedHashSet<>(requiresEngineRoles()))
                .build();
    }
}
