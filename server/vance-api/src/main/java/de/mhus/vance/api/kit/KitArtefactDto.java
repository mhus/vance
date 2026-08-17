package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One artefact an installed kit owns — a document path or a setting key,
 * together with the content hash at install time and the layer it came
 * from.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitArtefactDto {

    /** Document path (relative to the project document root) or setting key. */
    private String id;

    /**
     * {@code sha256:<hex>} over the content as installed. Answers exactly
     * one question: did the user touch this since the install?
     *
     * <p>Null for encrypted settings — their ciphertext is
     * non-deterministic, so a hash would compare noise. Those fall back
     * to "never overwrite an existing value" under
     * {@link KitPolicyAction#KEEP}.
     */
    private @Nullable String hash;

    /**
     * Name of the kit layer whose version won last-writer-wins — the
     * top-layer kit itself or one of its inherits. Export writes back
     * only artefacts of the top layer.
     */
    private String layer;
}
