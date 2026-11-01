package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.projects.ProjectLiveState;
import de.mhus.vance.api.projects.RunnerStatusDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Condensed view of a project for list responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class ProjectSummary {

    /** Business identifier — {@code ProjectDocument.name}. */
    private String name;

    private @Nullable String title;

    private @Nullable String projectGroupId;

    private boolean enabled;

    /**
     * Creation timestamp in milliseconds since epoch — used by clients
     * that want a "newest first" sort order (e.g. the Mobile project
     * picker). May be {@code null} for legacy seed data that was
     * imported before this field was tracked.
     */
    private @Nullable Long createdAtMs;

    /** Desired state ({@code ProjectStatus}), e.g. {@code RUNNING}, {@code IDLE}, {@code SUSPENDED}. */
    private @Nullable String desiredState;

    /** What the project is doing now, from the live state; {@code UNKNOWN} when nobody vouches. */
    private @Nullable ProjectLiveState liveState;

    /** After this instant {@link #liveState} no longer counts, ms since epoch. */
    private @Nullable Long liveExpiresAtMs;

    /** The runner serving the project (own binding, else the tenant's); absent when none is bound. */
    private @Nullable RunnerStatusDto runner;
}
