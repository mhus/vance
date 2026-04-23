package de.mhus.vance.shared.keystore;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.SecretKey;
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
 * Persistent crypto-key record. Package-private — external code works with
 * {@link KeyService} and JDK key types, never with this document.
 *
 * <p>Indexed so the two hot access paths ({@code latest by tenant+purpose} and
 * {@code lookup by keyId}) are served directly by Mongo.
 */
@Document(collection = "keystore_keys")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_purpose_kind_idx", def = "{ 'tenantId': 1, 'purpose': 1, 'kind': 1 }"),
        @CompoundIndex(name = "tenant_purpose_kind_keyId_idx", def = "{ 'tenantId': 1, 'purpose': 1, 'kind': 1, 'keyId': 1 }", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class KeyDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private String purpose = "";

    private KeyKind kind = KeyKind.PUBLIC;

    /** JDK algorithm name: {@code EC}, {@code RSA}, {@code AES}, {@code HmacSHA256}. */
    private String algorithm = "";

    /** Stable id for this specific key (UUID). Shared by the {@link KeyKind#PRIVATE}/{@link KeyKind#PUBLIC} pair. */
    private String keyId = "";

    /** Base64-encoded key bytes. {@code X509} for public, {@code PKCS#8} for private, raw for secret. */
    private String encoded = "";

    @CreatedDate
    private @Nullable Instant createdAt;

    private @Nullable Instant expiresAt;

    private boolean enabled = true;

    boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    static KeyDocument ofPublicKey(String tenantId, String purpose, String keyId, PublicKey key) {
        return KeyDocument.builder()
                .tenantId(tenantId)
                .purpose(purpose)
                .kind(KeyKind.PUBLIC)
                .algorithm(key.getAlgorithm())
                .keyId(keyId)
                .encoded(Base64.getEncoder().encodeToString(key.getEncoded()))
                .enabled(true)
                .build();
    }

    static KeyDocument ofPrivateKey(String tenantId, String purpose, String keyId, PrivateKey key) {
        return KeyDocument.builder()
                .tenantId(tenantId)
                .purpose(purpose)
                .kind(KeyKind.PRIVATE)
                .algorithm(key.getAlgorithm())
                .keyId(keyId)
                .encoded(Base64.getEncoder().encodeToString(key.getEncoded()))
                .enabled(true)
                .build();
    }

    static KeyDocument ofSecretKey(String tenantId, String purpose, String keyId, SecretKey key) {
        return KeyDocument.builder()
                .tenantId(tenantId)
                .purpose(purpose)
                .kind(KeyKind.SECRET)
                .algorithm(key.getAlgorithm())
                .keyId(keyId)
                .encoded(Base64.getEncoder().encodeToString(key.getEncoded()))
                .enabled(true)
                .build();
    }
}
