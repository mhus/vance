package de.mhus.vance.shared.database;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The one row that says whose database this is.
 *
 * <p>A fixed {@code _id} so there can only ever be one: two rows would be
 * two answers, and the guard needs exactly one.
 */
@Document(collection = "database_identity")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseIdentityDocument {

    /** The only id this collection uses. */
    public static final String SINGLETON_ID = "identity";

    @Id
    private String id;

    /** {@code brain}, {@code store} — see {@link DatabaseOwner#owner()}. */
    private String owner;

    /** When this database was first claimed, for forensics after a mix-up. */
    private @Nullable Instant claimedAt;

    /** Host that claimed it. Informational — the owner is what is enforced. */
    private @Nullable String claimedBy;
}
