package de.mhus.vance.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Live state of a runner, as reported by whoever manages it. Rides on
 * {@code ProjectSummary} and as the payload of the {@code runner-state}
 * signal. A client treats the entry as {@link RunnerPhase#UNKNOWN} once
 * {@link #expiresAtMs} has passed without a refresh.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("projects")
public class RunnerStatusDto {

    /** The runner's id, the join key to a project's binding. */
    private String runnerId;

    private RunnerPhase phase;

    /** Restart count of the runner container, when known. */
    private @Nullable Integer restarts;

    /** Last time the runner was seen ready, ms since epoch. */
    private @Nullable Long lastSeenAtMs;

    /** When the reporter wrote this, ms since epoch. */
    private @Nullable Long observedAtMs;

    /** After this instant the entry no longer counts, ms since epoch. */
    private @Nullable Long expiresAtMs;
}
