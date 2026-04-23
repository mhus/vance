package de.mhus.vance.shared.team;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Persistent team record, scoped to a tenant.
 *
 * <p>{@code tenantId} references {@code TenantDocument.name}; {@code members}
 * are {@code UserDocument.name} values inside the same tenant.
 */
@Document(collection = "teams")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String name = "";

    private @Nullable String title;

    /** Member usernames ({@code UserDocument.name} values). */
    @Builder.Default
    private List<String> members = new ArrayList<>();

    private boolean enabled = true;

    @CreatedDate
    private @Nullable Instant createdAt;
}
