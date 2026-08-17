package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One installed kit, persisted as {@code _vance/kits/installed/<id>.yaml}.
 * The presence of such a document is what "installed" means — a project
 * may carry any number of them.
 *
 * <p>The document is <b>entirely machine-generated</b> and rewritten in
 * full on every update; it can be reconstructed from the resolved build
 * tree at any time. Everything the user authors lives next door in
 * {@link KitConfigDto}.
 *
 * <p>Not to be confused with {@link KitManifestDto}: that one says
 * "this project <i>is</i> a kit source, export it", exists at most once
 * per project, and is written only when the user asks for it.
 *
 * <p>Spec: {@code planning/kit-installed-multi.md} §4.1.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitInstalledRecordDto {

    /**
     * Stable identity of this installation, derived from
     * {@code (origin.url, origin.path)} — also the document's file name.
     * Re-installing the same coordinates is an update of this record.
     */
    private String id;

    private KitMetadataDto kit;

    private KitOriginDto origin;

    /**
     * The top-layer {@code kit.yaml} as authored, parsed. Subsumes the
     * manifest's separate {@code inherits} and {@code resolvedInherits}
     * copies and lets the UI show description, version and the
     * visibility flags without touching the network.
     */
    private @Nullable KitDescriptorDto descriptor;

    /** What this kit wrote into the project, with hashes and layer origin. */
    @Builder.Default
    private KitArtefactsDto artefacts = KitArtefactsDto.builder().build();

    /** True when the resolved kit shipped any encrypted setting. */
    @Builder.Default
    private boolean hasEncryptedSecrets = false;

    /**
     * What the signature check said at install time, and which source
     * said it.
     *
     * <p>A record of the moment, not a live property: the kit tree is
     * gone once its artefacts are in the project, so this cannot be
     * re-derived — only replaced by the next update.
     */
    private @Nullable KitSignatureStatus signatureStatus;

    /** Id of the source this kit was loaded through, for the same reason. */
    private @Nullable String sourceId;
}
