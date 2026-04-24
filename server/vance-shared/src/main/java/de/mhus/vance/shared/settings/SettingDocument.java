package de.mhus.vance.shared.settings;

import de.mhus.vance.api.settings.SettingType;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent setting record. One document per
 * {@code (tenantId, referenceType, referenceId, key)} tuple.
 *
 * <p>{@code referenceType} + {@code referenceId} identify the owning scope
 * (e.g. {@code "project" / "literature-review"}). Look-up is always by
 * {@code name}-style identifiers, never by Mongo id.
 *
 * <p>When {@link #type} is {@link SettingType#PASSWORD}, {@link #value} holds
 * the AES-GCM ciphertext produced by
 * {@link de.mhus.vance.shared.crypto.AesEncryptionService}.
 */
@Document(collection = "settings")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_ref_key_idx",
                def = "{ 'tenantId': 1, 'referenceType': 1, 'referenceId': 1, 'key': 1 }",
                unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettingDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** Owning scope kind, e.g. {@code "tenant"}, {@code "project"}, {@code "user"}. */
    private String referenceType = "";

    /** {@code name} of the owning scope entity (not its Mongo id). */
    private String referenceId = "";

    /** Dot-notation, e.g. {@code "llm.default"}. */
    @Indexed
    private String key = "";

    /** Plaintext for non-password types; ciphertext for {@link SettingType#PASSWORD}. */
    private @Nullable String value;

    private SettingType type = SettingType.STRING;

    private @Nullable String description;

    @CreatedDate
    private @Nullable Instant createdAt;

    @LastModifiedDate
    private @Nullable Instant updatedAt;
}
