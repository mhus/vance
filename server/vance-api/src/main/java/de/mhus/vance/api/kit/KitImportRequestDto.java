package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request body for kit install / update / apply. The same shape covers
 * all three modes — the {@code mode} field selects behaviour.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitImportRequestDto {

    /** Project the kit is installed into. */
    private String projectId;

    /** Source location (url + path + branch + optional commit). */
    private KitInheritDto source;

    /**
     * Authentication token for the source repo. Stored on the user
     * scope as {@code kit.token.<host>} after a successful operation
     * so future calls can pre-fill the form.
     */
    private @Nullable String token;

    /**
     * Vault passphrase used to decrypt PASSWORD-settings shipped with
     * the kit. Required iff the resolved kit has
     * {@code hasEncryptedSecrets=true}.
     */
    private @Nullable String vaultPassword;

    /** {@code INSTALL}, {@code UPDATE} or {@code APPLY}. */
    private KitImportMode mode;

    /**
     * Update only: also delete project artefacts that were tracked in
     * the previous manifest but are absent in the new kit. Default
     * non-destructive (artefacts only drop out of the manifest).
     */
    @Builder.Default
    private boolean prune = false;

    /**
     * Apply only: skip PASSWORD-type settings entirely so existing
     * credentials in the project are preserved. Other artefacts still
     * overwrite silently.
     */
    @Builder.Default
    private boolean keepPasswords = false;

    /**
     * {@link KitSourceType#PROJECT} only: whether the source project hands
     * over the credentials its manifest declares. Ignored by every other
     * source type — there the question is answered by the vault passphrase.
     *
     * <p><b>Default on.</b> The manifest's {@code settings:} list is an
     * author's statement that a key belongs to the kit, so a kit declaring
     * its SMTP password means to ship it; suppressing that quietly leaves an
     * installation that does not work and nobody able to say why. Turned off
     * when the copy should carry the shape of the kit but not its secrets —
     * the report then names the keys that were left out.
     *
     * <p>Not to be confused with {@link #keepPasswords}, which is the
     * mirror-image question on the <em>target</em> side: whether credentials
     * already in the destination project survive the write.
     *
     * <p><b>{@code Boolean}, not {@code boolean}, and no {@code @Builder.Default}</b>
     * — the one place in this class where that matters. Lombok moves a
     * {@code @Builder.Default} initialiser onto the builder and drops it from
     * the field (see the note in {@code KitExporter.writeDescriptor}, which hit
     * the same thing), so a request deserialised by Jackson without this field
     * would arrive as {@code false}: the opposite of the intended default,
     * silently, on every REST call that does not mention it. The other flags
     * here default to {@code false} and hide the problem behind the zero value.
     *
     * <p>So {@code null} is a state with a meaning: "not stated", which the
     * server reads as on. The client only sends the field when a person
     * changed it.
     */
    private @Nullable Boolean copySecrets;

    /**
     * Install/update only: additionally mark this project as a kit
     * <i>source</i> by writing {@code _vance/kits/manifest.yaml}, which
     * is what {@code export} works from.
     *
     * <p>Default off — the everyday case is installing a kit, not
     * authoring one. Kit developers turn it on, either here or later via
     * promote (which builds the manifest from an existing install
     * record, no re-clone needed).
     *
     * <p>Spec: {@code planning/kit-installed-multi.md} §2.1.
     */
    @Builder.Default
    private boolean writeManifest = false;

    /**
     * What the caller asks the source for, passed through to it verbatim.
     *
     * <p>Comes from a provisioning entry's {@code params:} and is empty for
     * a hand-typed install. Free-form because only the far end knows its
     * own options, and open-ended for the same reason — unlike the source
     * coordinates, which are the kit's identity and fixed.
     *
     * <p>Not secret-resolved anywhere: a value here is handed to a third
     * party, and the credential in {@link #token} is the field meant for
     * that.
     */
    @Builder.Default
    private Map<String, Object> params = Map.of();

    /**
     * Opaque token the provisioning path supplies so the install record
     * can remember what the source said it was handing over. Null for
     * every hand-typed install, which is then simply never change-checked.
     */
    private @Nullable String provisioningStamp;
}
