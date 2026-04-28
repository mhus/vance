package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.PromptMode;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Engine-owned default configuration for processes of this engine.
 * Used by engines whose persona, tool-cut, and params are defined by
 * code rather than by recipe — Vance is the canonical example: her
 * engine-level mechanics (Activity-Log, Peer-Events, cross-project
 * spawn) cannot be expressed as recipe config, so the prompt and
 * params live with the engine class itself.
 *
 * <p>When {@link ThinkEngine#bundledConfig()} returns a value, the
 * {@code SessionChatBootstrapper} (and any other spawner that picks
 * up this signal) skips recipe resolution entirely and creates the
 * {@code ThinkProcessDocument} directly from the bundled fields.
 *
 * <p>Field semantics mirror {@link AppliedRecipe} so the downstream
 * {@code ThinkProcessService.create} call remains uniform:
 *
 * <ul>
 *   <li>{@code params} — engine params written to
 *       {@code ThinkProcessDocument.engineParams}.</li>
 *   <li>{@code promptOverride} — system prompt (markdown-flavoured)
 *       used by the engine's chat machinery.</li>
 *   <li>{@code promptOverrideSmall} — variant for small models.</li>
 *   <li>{@code promptMode} — APPEND or REPLACE relative to the
 *       engine's fallback prompt.</li>
 *   <li>{@code intentCorrection} / {@code dataRelayCorrection} —
 *       optional validator templates.</li>
 *   <li>{@code allowedTools} — engine-default tool set if it differs
 *       from {@link ThinkEngine#allowedTools()}; usually
 *       {@code null} so the engine's own declaration wins.</li>
 * </ul>
 *
 * <p>See {@code specification/vance-engine.md} §1.2 for the
 * recipe-vs-engine separation rationale.
 */
public record EngineBundledConfig(
        Map<String, Object> params,
        @Nullable String promptOverride,
        @Nullable String promptOverrideSmall,
        PromptMode promptMode,
        @Nullable String intentCorrection,
        @Nullable String dataRelayCorrection,
        @Nullable Set<String> allowedTools) {
}
