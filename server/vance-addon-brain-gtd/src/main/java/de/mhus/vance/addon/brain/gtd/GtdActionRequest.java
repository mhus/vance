package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /action} (create) and {@code PATCH /action}
 * (in-place field patch). On create, {@code title} is required and
 * {@code project} chooses the target folder; on patch every field is
 * optional and {@code null} leaves the existing value.
 */
@GenerateTypeScript("gtd")
public record GtdActionRequest(
        @Nullable String title,
        @Nullable String when,
        @Nullable String deadline,
        @Nullable List<String> contexts,
        @Nullable String project,
        @Nullable Boolean done,
        @Nullable String body) {}
