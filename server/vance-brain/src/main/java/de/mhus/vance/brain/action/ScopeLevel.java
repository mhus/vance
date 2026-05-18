package de.mhus.vance.brain.action;

/**
 * Sandbox classification for {@code VanceScriptApi}-bound script
 * execution. Picked per call site based on
 * {@link TriggerKind#isProcessScoped()}:
 *
 * <ul>
 *   <li>{@link #TRIGGER_SCOPED} — scheduler, event or manual REST
 *       trigger without an enclosing Process. Tools annotated with
 *       {@link de.mhus.vance.toolpack.SpawnTool} are denied to keep
 *       triggers from sneaking spawn behaviour past the
 *       {@code workflow:}-route.</li>
 *   <li>{@link #PROCESS_SCOPED} — workflow task or LLM tool call from
 *       inside a running Process. Full
 *       {@code VanceScriptApi} surface including spawn tools.</li>
 * </ul>
 *
 * <p>See {@code planning/trigger-actions.md} §8.
 */
public enum ScopeLevel {
    TRIGGER_SCOPED,
    PROCESS_SCOPED
}
