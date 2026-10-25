package de.mhus.vance.api.users;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One account quota of a tenant: how many accounts occupy a place (every
 * status except DISABLED) and the configured ceiling.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("users")
public class UserQuotaUsageDto {

    private long active;

    /** {@code 0} = no limit. */
    private int limit;
}
