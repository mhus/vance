package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Detached signature over a kit, shipped as {@code kit.sig.yaml} beside
 * {@code kit.yaml}.
 *
 * <p>Detached rather than embedded so the signed content stays exactly
 * the bytes that were signed — a signature inside the descriptor would
 * have to be excluded from its own input, and every reader would need to
 * agree on how.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitSignatureDto {

    /** Currently always {@code Ed25519}. Named so a future change is legible. */
    private String algorithm;

    /** Which key signed this — lets a source rotate keys without breaking old kits. */
    private String keyId;

    /** {@code sha256:<hex>} over the kit tree, as computed by both ends. */
    private String treeHash;

    private @Nullable Instant signedAt;

    /** Base64 signature over the payload described in {@code KitTreeHash}. */
    private String signature;
}
