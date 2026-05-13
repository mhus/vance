package de.mhus.vance.api.hooks;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Hook implementation kind.
 *
 * <ul>
 *   <li>{@link #JS} — deterministic; sandboxed JavaScript via GraalJS.</li>
 *   <li>{@link #LLM} — LLM emits a structured action list which the
 *       dispatcher executes against the host-API.</li>
 * </ul>
 *
 * <p>See {@code specification/hooks.md} §4.
 */
@GenerateTypeScript("hooks")
public enum HookType {
    JS,
    LLM
}
