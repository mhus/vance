package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
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
}
