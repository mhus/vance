package de.mhus.vance.shared.events;

import de.mhus.vance.api.events.EventSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Result of loading and parsing one event YAML document.
 *
 * <p>Bearer secrets are kept as <strong>references</strong>, not values:
 * {@link #tokenLiteral} carries an inline {@code auth.token:} when set
 * (cheap convenience for tests), {@link #tokenSettingKey} carries the
 * setting-cascade key when the YAML used {@code auth.tokenSetting:}.
 * The actual secret comparison happens in {@code EventService} which
 * resolves the setting via {@link de.mhus.vance.shared.settings.SettingService}.
 */
public record ResolvedEvent(
        String name,
        String yaml,
        EventSource source,
        @Nullable String documentId,
        @Nullable String createdBy,
        @Nullable String description,
        /** Workflow name this event spawns. */
        String workflow,
        /** {@code false} disables the event — REST returns 404. */
        boolean enabled,
        /** Upper-case HTTP methods that may trigger this event ({@code GET}, {@code POST}). Empty = both. */
        Set<String> methods,
        /** Inline bearer literal — exclusive with {@link #tokenSettingKey}. {@code null} → no auth or setting-based. */
        @Nullable String tokenLiteral,
        /** Setting key resolved via the cascade — exclusive with {@link #tokenLiteral}. */
        @Nullable String tokenSettingKey,
        /** Static params passed into the spawned workflow run. */
        Map<String, Object> params,
        @Nullable String runAs,
        List<String> tags) {

    /** {@code true} when bearer authentication is required. */
    public boolean requiresAuth() {
        return tokenLiteral != null || tokenSettingKey != null;
    }

    /** {@code true} when the given HTTP method is accepted by this event. */
    public boolean acceptsMethod(String method) {
        if (methods.isEmpty()) return true;
        return methods.contains(method.toUpperCase(java.util.Locale.ROOT));
    }
}
