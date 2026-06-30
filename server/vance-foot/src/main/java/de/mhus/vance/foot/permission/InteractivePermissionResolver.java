package de.mhus.vance.foot.permission;

/**
 * Resolves an {@link PermissionDecision#ASK} verdict to a final
 * {@link PermissionDecision#ALLOW}/{@link PermissionDecision#DENY} by
 * asking the user. Implemented by {@link PermissionPrompt}; abstracted so
 * the {@code tools} layer's gate does not depend on the UI-coupled prompt
 * implementation (and can be stubbed in tests).
 */
public interface InteractivePermissionResolver {

    /**
     * @param toolName the tool being gated (for display)
     * @param domain   which rule domain a persisted "always" answer lands in
     * @param subject  canonical path (PATHS) or raw command (COMMANDS),
     *                 shown to the user and stored on an "always" answer
     */
    PermissionDecision resolve(String toolName, PermissionDomain domain, String subject);
}
