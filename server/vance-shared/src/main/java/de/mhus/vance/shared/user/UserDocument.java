package de.mhus.vance.shared.user;

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
 * Persistent user record, scoped to a tenant.
 *
 * <p>{@code tenantId} references {@code TenantDocument.name} (not its MongoDB
 * id), per the entity convention in {@code CLAUDE.md}. {@code name} is the
 * technical unique username inside the tenant. {@code title} is an optional
 * display name; {@code passwordHash} is produced by the caller and stored
 * verbatim.
 */
@Document(collection = "users")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String name = "";

    private @Nullable String passwordHash;

    private @Nullable String title;

    private @Nullable String email;

    private UserStatus status = UserStatus.ACTIVE;

    @CreatedDate
    private @Nullable Instant createdAt;
}
