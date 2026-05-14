package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tenant-wide catalog of pre-configured kits, presented at project-create
 * time as a dropdown / CLI flag / Eddie tool. Persisted as YAML document
 * {@code config/project-kits.yaml} inside the tenant's {@code _vance}
 * project — see {@code specification/project-kits-catalog.md}.
 *
 * <p>No cascade across tenants. The {@code _vance} system tenant holds
 * the seed catalog that gets copied into every newly-created tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ProjectKitsCatalogDto {

    /** Schema version. Current version is {@code 1}. */
    private int version;

    @Builder.Default
    private List<ProjectKitEntry> kits = new ArrayList<>();
}
