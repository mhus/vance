package de.mhus.vance.shared.tenant;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent tenant record.
 *
 * {@code name} is the technical, unique business identifier used everywhere else
 * in the system (e.g. {@code "default"}). {@code id} is the internal MongoDB id
 * and is not exposed outside {@code vance-shared}.
 */
@Document(collection = "tenants")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantDocument {

    @Id
    private @Nullable String id;

    @Indexed(unique = true)
    private String name = "";

    private @Nullable String title;

    private boolean enabled = true;

    @CreatedDate
    private @Nullable Instant createdAt;
}
