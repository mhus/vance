package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Persisted as document {@code _vance/kit-manifest.yaml} inside the
 * project — tracks which artefacts were installed by the active kit
 * (top-layer only, never inherits). Existence of this document means a
 * kit is active in the project; absence means no active kit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitManifestDto {

    private KitMetadataDto kit;

    private KitOriginDto origin;

    /** Document paths (relative to the project document root). */
    @Builder.Default
    private List<String> documents = new ArrayList<>();

    /** Setting keys (within the project scope). */
    @Builder.Default
    private List<String> settings = new ArrayList<>();

    /** Server-tool names. */
    @Builder.Default
    private List<String> tools = new ArrayList<>();

    /**
     * Original {@code inherits:} list copied verbatim from the
     * top-layer {@code kit.yaml}. Persisted so {@code export} can
     * re-emit the descriptor without re-cloning.
     */
    @Builder.Default
    private List<KitInheritDto> inherits = new ArrayList<>();

    /**
     * Names of every kit (top-layer + transitive inherits) that
     * contributed to the resolved tree at install time. Diagnostic
     * only — not the source of truth for re-resolution.
     */
    @Builder.Default
    private List<String> resolvedInherits = new ArrayList<>();

    /**
     * Per-inherit ownership of installed artefacts. One entry per
     * inherit layer that actually contributed at least one artefact
     * after last-writer-wins resolution; layers that were fully
     * shadowed by the top layer (or by a later inherit) do not
     * appear. The top-layer's own contributions stay in
     * {@link #documents}/{@link #settings}/{@link #tools}.
     *
     * <p>Each path/key/name appears in exactly one place across the
     * manifest (either the top fields or one of these entries) — that
     * invariant is what makes prune-on-update tractable.
     */
    @Builder.Default
    private List<InheritArtefactsDto> inheritArtefacts = new ArrayList<>();

    /**
     * Set when the top-layer (or any of its inherits) shipped any
     * PASSWORD-type setting. Used by the export-form to know whether
     * a vault passphrase prompt is needed.
     */
    @Builder.Default
    private boolean hasEncryptedSecrets = false;
}
