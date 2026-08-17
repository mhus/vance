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
 * One kit a tenant is entitled to, as the brain passes it on to the UI.
 *
 * <p>Carries {@code sourceUrl} and {@code kitId} because those two are
 * exactly what an install needs — they are the {@code (url, path)} pair
 * that identifies the kit. The user picks a row; nobody types a url.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitLibraryEntryDto {

    /** Library this came from — the {@code url} half of the install identity. */
    private String sourceUrl;

    /** Id of the source in {@code kit-sources.yaml}, for display. */
    private String sourceId;

    /** The {@code path} half. Together with {@link #sourceUrl} it addresses the kit. */
    private String kitId;

    private @Nullable String vendor;

    private String displayName;

    private @Nullable String description;

    private @Nullable String license;

    private @Nullable String version;

    private @Nullable Instant licenseExpiresAt;

    /**
     * False when the entitlement resolves to no deliverable version.
     * Shown rather than hidden — a tenant who owns something and cannot
     * download it deserves to see why, not to wonder where it went.
     */
    @Builder.Default
    private boolean downloadable = true;

    /** True when this kit is already installed in the project being looked at. */
    @Builder.Default
    private boolean installed = false;
}
