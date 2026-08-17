package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitSignaturePolicy;
import de.mhus.vance.api.kit.KitSignatureStatus;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.shared.kit.KitException;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Applies a source's signature policy to a freshly loaded kit.
 *
 * <p>Runs per <b>layer</b>, not per install: a kit's inherits may come
 * from entirely different sources, and a trusted top layer says nothing
 * about a base kit pulled from somewhere else. Checking only the kit the
 * user named would let an unverified inherit walk in behind a verified
 * one.
 *
 * <p>Spec: {@code planning/kit-shop.md} §4 E3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitSignatureGate {

    /**
     * Let a loaded kit through, or refuse it.
     *
     * @param kitRoot directory holding {@code kit.yaml}
     * @param descriptor the parsed descriptor, whose purchase claims are
     *        covered by the signature
     * @param config the source this layer came from — its policy and key
     * @throws KitException when the policy is {@code required} and the
     *         kit does not satisfy it
     */
    public KitSignatureStatus enforce(
            Path kitRoot, KitDescriptorDto descriptor, KitSourceDto config) {
        KitSignaturePolicy policy = config.getSignature() == null
                ? KitSignaturePolicy.OFF
                : config.getSignature();
        if (policy == KitSignaturePolicy.OFF) {
            // Not checked, so nothing to claim either way. A kit that
            // happens to carry a valid signature is still recorded as
            // unsigned when nobody looked — saying "verified" would assert
            // a check that did not happen.
            return KitSignatureStatus.UNSIGNED;
        }

        KitSignature.Result result =
                KitSignature.verify(kitRoot, descriptor, config.getPublicKey());
        if (result == KitSignature.Result.VALID) {
            log.debug("KitSignatureGate: '{}' from source '{}' verified",
                    descriptor.getName(), config.getId());
            return KitSignatureStatus.VERIFIED;
        }

        String explanation = explain(result, descriptor.getName(), config);
        if (policy == KitSignaturePolicy.REQUIRED) {
            throw new KitException(explanation);
        }
        // WARN exists for sources in transition — say it loudly, let it pass,
        // and leave a mark on the record so it is findable afterwards.
        log.warn("KitSignatureGate: {}", explanation);
        return KitSignatureStatus.FAILED;
    }

    /**
     * Name the actual problem. "Signature verification failed" leaves the
     * user guessing between four unrelated causes with four different
     * fixes.
     */
    private static String explain(
            KitSignature.Result result, String kitName, KitSourceDto config) {
        return switch (result) {
            case MISSING -> "kit '" + kitName + "' from source '" + config.getId()
                    + "' carries no signature, but that source requires one";
            case NO_KEY -> "kit '" + kitName + "' is signed, but source '" + config.getId()
                    + "' has no publicKey configured to verify it against —"
                    + " add one to " + KitSourceRegistry.SOURCES_PATH;
            case TREE_MISMATCH -> "kit '" + kitName + "' from source '" + config.getId()
                    + "' does not match its signature: the content changed after signing";
            case INVALID -> "kit '" + kitName + "' from source '" + config.getId()
                    + "' has an invalid signature — wrong key, or the signature was altered";
            case VALID -> "";
        };
    }
}
