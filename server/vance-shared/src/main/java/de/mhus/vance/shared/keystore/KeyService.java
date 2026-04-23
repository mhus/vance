package de.mhus.vance.shared.keystore;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Tenant-scoped crypto-key store.
 *
 * <p>v1 supports ECC signing keys only ({@code secp256r1}) — enough for JWT ES256.
 * Symmetric / RSA paths exist at the document level and can be wired later
 * without touching the API.
 *
 * <p>All keys are addressed by {@code (tenantId, purpose)}. A single pair can
 * have multiple key versions (rotation); newest wins on signing, all valid ones
 * are returned on verification.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KeyService {

    private static final String EC_CURVE = "secp256r1";
    private static final String EC_ALGORITHM = "EC";

    private final KeyRepository repository;

    /** Whether any signing key exists for this tenant+purpose. */
    public boolean hasSigningKey(String tenantId, String purpose) {
        return repository.existsByTenantIdAndPurposeAndKind(tenantId, purpose, KeyKind.PRIVATE);
    }

    /** Newest enabled, non-expired private key for {@code (tenantId, purpose)}. */
    public Optional<PrivateKey> getLatestPrivateKey(String tenantId, String purpose) {
        return repository
                .findTop1ByTenantIdAndPurposeAndKindOrderByCreatedAtDesc(tenantId, purpose, KeyKind.PRIVATE)
                .filter(KeyDocument::isEnabled)
                .filter(k -> !k.isExpired())
                .flatMap(this::toPrivateKey);
    }

    /**
     * All enabled, non-expired public keys for {@code (tenantId, purpose)}, newest
     * first. Used for signature verification with key rotation.
     */
    public List<PublicKey> getPublicKeys(String tenantId, String purpose) {
        return repository
                .findAllByTenantIdAndPurposeAndKindOrderByCreatedAtDesc(tenantId, purpose, KeyKind.PUBLIC)
                .stream()
                .filter(KeyDocument::isEnabled)
                .filter(k -> !k.isExpired())
                .map(k -> toPublicKey(k).orElse(null))
                .filter(k -> k != null)
                .toList();
    }

    /** Generates a new ECC {@code secp256r1} key pair (JWT ES256). */
    public KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(EC_ALGORITHM);
            kpg.initialize(new ECGenParameterSpec(EC_CURVE));
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("EC key pair generation failed", e);
        }
    }

    /**
     * Persists {@code keyPair} as a linked pair of {@link KeyKind#PRIVATE} and
     * {@link KeyKind#PUBLIC} documents. Returns the shared {@code keyId} so callers
     * can correlate later (e.g. for a {@code kid} header).
     */
    public String storeKeyPair(String tenantId, String purpose, KeyPair keyPair) {
        String keyId = UUID.randomUUID().toString();
        repository.save(KeyDocument.ofPrivateKey(tenantId, purpose, keyId, keyPair.getPrivate()));
        repository.save(KeyDocument.ofPublicKey(tenantId, purpose, keyId, keyPair.getPublic()));
        log.info("Stored new key pair tenant='{}' purpose='{}' keyId='{}' algorithm='{}'",
                tenantId, purpose, keyId, keyPair.getPrivate().getAlgorithm());
        return keyId;
    }

    /** Convenience: generate + store + return the id — this is the one-shot „rotate now". */
    public String createAndStoreEcKeyPair(String tenantId, String purpose) {
        return storeKeyPair(tenantId, purpose, generateEcKeyPair());
    }

    /** Deletes every key (private+public+secret) for the given tenant+purpose. */
    public long deleteAll(String tenantId, String purpose) {
        return repository.deleteByTenantIdAndPurpose(tenantId, purpose);
    }

    private Optional<PrivateKey> toPrivateKey(KeyDocument doc) {
        try {
            byte[] encoded = Base64.getDecoder().decode(doc.getEncoded());
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encoded);
            return Optional.of(KeyFactory.getInstance(doc.getAlgorithm()).generatePrivate(spec));
        } catch (Exception e) {
            log.warn("Failed to decode private key tenant='{}' purpose='{}' keyId='{}': {}",
                    doc.getTenantId(), doc.getPurpose(), doc.getKeyId(), e.toString());
            return Optional.empty();
        }
    }

    private Optional<PublicKey> toPublicKey(KeyDocument doc) {
        try {
            byte[] encoded = Base64.getDecoder().decode(doc.getEncoded());
            X509EncodedKeySpec spec = new X509EncodedKeySpec(encoded);
            return Optional.of(KeyFactory.getInstance(doc.getAlgorithm()).generatePublic(spec));
        } catch (Exception e) {
            log.warn("Failed to decode public key tenant='{}' purpose='{}' keyId='{}': {}",
                    doc.getTenantId(), doc.getPurpose(), doc.getKeyId(), e.toString());
            return Optional.empty();
        }
    }
}
