package de.mhus.vance.api.users;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The tenant's team quota: how many teams are enabled and the configured ceiling. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("users")
public class TeamQuotaDto {

    private long active;

    /** {@code 0} = no limit. */
    private int limit;
}
