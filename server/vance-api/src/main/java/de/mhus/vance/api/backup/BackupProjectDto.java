package de.mhus.vance.api.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of {@link BackupProjectsResponse}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("backup")
public class BackupProjectDto {

    /** Project name (the {@code projectId} on documents). */
    private String name;

    /** Display title; {@code null} falls back to {@link #name} on the client. */
    private String title;

    /** Whether the project is currently enabled. Disabled projects only appear when explicitly requested. */
    private boolean enabled;
}
