package de.mhus.vance.brain.suggestion;

import de.mhus.vance.shared.permission.SecurityContext;
import org.jspecify.annotations.Nullable;

/**
 * One keystroke's question to a {@link SuggestionSource}.
 *
 * @param subject   who types — every source reads with these rights
 * @param userLogin the typing user's login, {@code null} for headless callers
 * @param projectId the project the form belongs to (READ already checked),
 *                  {@code null} when the form has none
 * @param query     what has been typed of the current value, trimmed, never blank
 * @param limit     at most this many suggestions
 */
public record SuggestionRequest(
        String tenantId,
        SecurityContext subject,
        @Nullable String userLogin,
        @Nullable String projectId,
        String query,
        int limit) {}
