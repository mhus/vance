package de.mhus.vance.api.users;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The tenant's account quotas: people ({@link #seats}) and service accounts
 * ({@link #serviceAccounts}, Vance-internal {@code _vance-*} identities
 * excluded), counted and limited independently.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("users")
public class UserQuotaDto {

    private UserQuotaUsageDto seats;

    private UserQuotaUsageDto serviceAccounts;
}
