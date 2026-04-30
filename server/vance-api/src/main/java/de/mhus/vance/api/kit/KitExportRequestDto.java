package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code kit_export} — pushes the active kit's
 * top-layer back into a git repository. Defaults are read from the
 * existing {@code kit-manifest.yaml#origin} when available.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitExportRequestDto {

    /** Project to export from — must have an active kit-manifest. */
    private String projectId;

    /** Target repo URL. Defaults to {@code manifest.origin.url}. */
    private @Nullable String url;

    /** Sub-path inside the repo. Defaults to {@code manifest.origin.path}. */
    private @Nullable String path;

    /** Branch to push to. Defaults to {@code manifest.origin.branch}. */
    private @Nullable String branch;

    /** Auth token. Pre-filled from the user's {@code kit.token.<host>}. */
    private @Nullable String token;

    /**
     * Vault passphrase used to re-encrypt PASSWORD-settings into the
     * portable export. Required iff the manifest references any
     * PASSWORD-settings.
     */
    private @Nullable String vaultPassword;

    /** Commit message. Defaults to {@code vance-export: <kit-name>@<sha-short>}. */
    private @Nullable String commitMessage;
}
