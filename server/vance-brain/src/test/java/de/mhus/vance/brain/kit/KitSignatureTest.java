package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitSignatureDto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tree hashing and signature verification — spec:
 * {@code planning/kit-shop.md} §5.3.
 *
 * <p>Signs with a freshly generated key pair rather than a fixture: the
 * point is that a signature produced by the documented recipe verifies,
 * and a canned blob would only prove that one blob still parses.
 */
class KitSignatureTest {

    @TempDir
    Path tmp;

    private KeyPair keys;
    private Path kit;

    @BeforeEach
    void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance(KitSignature.ALGORITHM).generateKeyPair();
        kit = tmp.resolve("kit");
        Files.createDirectories(kit.resolve("documents"));
        Files.writeString(kit.resolve("kit.yaml"),
                "name: signed-kit\ndescription: a signed kit\n");
        Files.writeString(kit.resolve("documents/intro.md"), "hello\n");
    }

    // ── tree hash ────────────────────────────────────────────────────

    @Test
    void treeHash_isStableAcrossRuns() {
        assertThat(KitTreeHash.of(kit)).isEqualTo(KitTreeHash.of(kit));
    }

    @Test
    void treeHash_changesWhenContentChanges() throws IOException {
        String before = KitTreeHash.of(kit);
        Files.writeString(kit.resolve("documents/intro.md"), "hello, again\n");
        assertThat(KitTreeHash.of(kit)).isNotEqualTo(before);
    }

    @Test
    void treeHash_changesWhenAFileIsAdded() throws IOException {
        String before = KitTreeHash.of(kit);
        Files.writeString(kit.resolve("documents/extra.md"), "more\n");
        assertThat(KitTreeHash.of(kit)).isNotEqualTo(before);
    }

    @Test
    void treeHash_ignoresTheSignatureFileItself() throws IOException {
        // The signature cannot be part of what it signs, or writing it
        // would invalidate it.
        String before = KitTreeHash.of(kit);
        Files.writeString(kit.resolve(KitSignature.SIGNATURE_FILENAME), "algorithm: Ed25519\n");
        assertThat(KitTreeHash.of(kit)).isEqualTo(before);
    }

    // ── verification ─────────────────────────────────────────────────

    @Test
    void verify_correctlySignedKit_isValid() throws Exception {
        KitDescriptorDto descriptor = descriptor().build();
        sign(descriptor);

        assertThat(KitSignature.verify(kit, descriptor, publicKeyPem()))
                .isEqualTo(KitSignature.Result.VALID);
    }

    @Test
    void verify_withoutSignatureFile_reportsMissing() {
        assertThat(KitSignature.verify(kit, descriptor().build(), publicKeyPem()))
                .isEqualTo(KitSignature.Result.MISSING);
    }

    @Test
    void verify_withoutConfiguredKey_reportsNoKey() throws Exception {
        KitDescriptorDto descriptor = descriptor().build();
        sign(descriptor);

        // A signed kit and nothing to check it against is its own problem,
        // distinct from an unsigned one — the fix is to configure a key.
        assertThat(KitSignature.verify(kit, descriptor, null))
                .isEqualTo(KitSignature.Result.NO_KEY);
    }

    @Test
    void verify_contentChangedAfterSigning_reportsTreeMismatch() throws Exception {
        KitDescriptorDto descriptor = descriptor().build();
        sign(descriptor);
        Files.writeString(kit.resolve("documents/intro.md"), "tampered\n");

        assertThat(KitSignature.verify(kit, descriptor, publicKeyPem()))
                .isEqualTo(KitSignature.Result.TREE_MISMATCH);
    }

    @Test
    void verify_signedByAnotherKey_isInvalid() throws Exception {
        KitDescriptorDto descriptor = descriptor().build();
        sign(descriptor);
        KeyPair stranger = KeyPairGenerator.getInstance(KitSignature.ALGORITHM).generateKeyPair();

        assertThat(KitSignature.verify(kit, descriptor, pem(stranger)))
                .isEqualTo(KitSignature.Result.INVALID);
    }

    @Test
    void verify_licensedToChangedAfterSigning_isInvalid() throws Exception {
        // The whole reason the payload covers the purchase claims: editing
        // the tenant a kit is licensed to must not leave a valid signature.
        KitDescriptorDto signed = descriptor().licensedTo("acme-tenant").build();
        sign(signed);

        KitDescriptorDto altered = descriptor().licensedTo("someone-else").build();
        assertThat(KitSignature.verify(kit, altered, publicKeyPem()))
                .isEqualTo(KitSignature.Result.INVALID);
    }

    @Test
    void verify_purchaseIdRemovedAfterSigning_isInvalid() throws Exception {
        // Absent claims sign as the empty string, so deleting a field is
        // not a way to keep a signature valid.
        KitDescriptorDto signed = descriptor().purchaseId("pur_7f3a").build();
        sign(signed);

        assertThat(KitSignature.verify(kit, descriptor().build(), publicKeyPem()))
                .isEqualTo(KitSignature.Result.INVALID);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static KitDescriptorDto.KitDescriptorDtoBuilder descriptor() {
        return KitDescriptorDto.builder().name("signed-kit").description("a signed kit");
    }

    /** Sign the kit exactly the way the delivery end is specified to. */
    private void sign(KitDescriptorDto descriptor) throws Exception {
        String treeHash = KitTreeHash.of(kit);
        byte[] payload = KitTreeHash.signedPayload(
                treeHash,
                descriptor.getLicensedTo() == null ? "" : descriptor.getLicensedTo(),
                descriptor.getPurchaseId() == null ? "" : descriptor.getPurchaseId(),
                descriptor.getLicenseExpiresAt() == null
                        ? "" : descriptor.getLicenseExpiresAt().toString());

        Signature signer = Signature.getInstance(KitSignature.ALGORITHM);
        signer.initSign(keys.getPrivate());
        signer.update(payload);

        Files.writeString(kit.resolve(KitSignature.SIGNATURE_FILENAME),
                KitYamlMapper.writeSignature(KitSignatureDto.builder()
                        .algorithm(KitSignature.ALGORITHM)
                        .keyId("test-key")
                        .treeHash(treeHash)
                        .signedAt(Instant.parse("2026-08-17T10:00:00Z"))
                        .signature(Base64.getEncoder().encodeToString(signer.sign()))
                        .build()),
                StandardCharsets.UTF_8);
    }

    private String publicKeyPem() {
        return pem(keys);
    }

    private static String pem(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }
}
