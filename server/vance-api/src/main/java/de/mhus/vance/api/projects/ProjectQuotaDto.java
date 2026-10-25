package de.mhus.vance.api.projects;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The tenant's active-project quota: how many projects are active (not
 * archived, system projects excluded) and the configured ceiling.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("projects")
public class ProjectQuotaDto {

    private long active;

    /** {@code 0} = no limit. */
    private int limit;
}
