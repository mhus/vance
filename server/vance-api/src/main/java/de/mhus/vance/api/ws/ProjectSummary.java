package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
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
}
