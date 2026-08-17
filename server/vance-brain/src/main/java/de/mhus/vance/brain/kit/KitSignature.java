package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitSignatureDto;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.kit.KitTreeHash;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Verifies a kit's detached signature.
 *
 * <p>Verification only — Vancetope never signs. Signing needs the
 * private key and the purchase context, both of which live at the
 * delivering end. A brain that could sign could also sign for itself,
 * which would make the whole exercise decorative.
 *
 * <p>Ed25519 via the JDK: no parameter choices to get wrong, short keys,
 * nothing to add to the dependency list.
 */
@Slf4j
public final class KitSignature {

    /** Detached signature, beside {@code kit.yaml}. Defined with the hashing it excludes. */
    public static final String SIGNATURE_FILENAME = KitTreeHash.SIGNATURE_FILENAME;

    public static final String ALGORITHM = "Ed25519";

    private KitSignature() {}

    /** Why a verification failed, in terms the user can act on. */
    public enum Result {
        /** Signature present, key known, content matches. */
        VALID,
        /** No {@code kit.sig.yaml} in the kit. */
        MISSING,
        /** Signature present but no key configured to check it against. */
        NO_KEY,
        /** The kit's content is not what was signed. */
        TREE_MISMATCH,
        /** Cryptographic check failed — wrong key, tampered signature. */
        INVALID
    }

    /** Read the signature file, or null when the kit ships none. */
    public static @Nullable KitSignatureDto read(Path kitRoot) {
        Path file = kitRoot.resolve(SIGNATURE_FILENAME);
        if (!Files.isRegularFile(file)) return null;
        try {
            return KitYamlMapper.parseSignature(Files.readString(file));
        } catch (java.io.IOException e) {
            throw new KitException("failed to read " + SIGNATURE_FILENAME, e);
        }
    }

    /**
     * Check a kit against its signature.
     *
     * @param kitRoot directory holding {@code kit.yaml}
     * @param descriptor the parsed descriptor — its purchase claims are
     *        part of what was signed
     * @param publicKeyPem the key this source signs with, or null when
     *        none is configured
     */
    public static Result verify(
            Path kitRoot, KitDescriptorDto descriptor, @Nullable String publicKeyPem) {
        KitSignatureDto signature = read(kitRoot);
        if (signature == null) return Result.MISSING;
        if (publicKeyPem == null || publicKeyPem.isBlank()) return Result.NO_KEY;

        // Cheap check first, and the one whose failure is explainable:
        // content that does not match the signed tree is a different kit,
        // not a broken signature.
        String actual = KitTreeHash.of(kitRoot);
        if (!actual.equals(signature.getTreeHash())) {
            log.warn("KitSignature: tree hash mismatch — signed {}, found {}",
                    signature.getTreeHash(), actual);
            return Result.TREE_MISMATCH;
        }

        byte[] payload = KitTreeHash.signedPayload(
                signature.getTreeHash(),
                nullToEmpty(descriptor.getLicensedTo()),
                nullToEmpty(descriptor.getPurchaseId()),
                descriptor.getLicenseExpiresAt() == null
                        ? "" : descriptor.getLicenseExpiresAt().toString());
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(parsePublicKey(publicKeyPem));
            verifier.update(payload);
            boolean ok = verifier.verify(Base64.getDecoder().decode(signature.getSignature()));
            return ok ? Result.VALID : Result.INVALID;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("KitSignature: verification failed: {}", e.toString());
            return Result.INVALID;
        }
    }

    /** Parse a PEM-encoded X.509 public key. */
    static PublicKey parsePublicKey(String pem) throws GeneralSecurityException {
        String base64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance(ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(der));
    }

    /**
     * Absent claims sign as the empty string rather than being left out.
     * Otherwise a kit with no purchase and one whose purchaseId was
     * deleted would produce the same payload, and removing a field would
     * be a way to keep a signature valid.
     */
    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    /** UTF-8, spelled out because the payload must not depend on a platform default. */
    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
