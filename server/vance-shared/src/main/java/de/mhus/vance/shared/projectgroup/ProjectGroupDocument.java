package de.mhus.vance.shared.projectgroup;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent project-group record, scoped to a tenant.
 *
 * <p>{@code tenantId} references {@code TenantDocument.name}. {@code name} is
 * the unique business identifier inside the tenant.
 */
@Document(collection = "project_groups")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectGroupDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String name = "";

    private @Nullable String title;

    private boolean enabled = true;

    @CreatedDate
    private @Nullable Instant createdAt;
}
