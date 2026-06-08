package de.mhus.vance.api.addon;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of an installed addon as shown in the Insights
 * editor's Addons tab. Combines the {@code addons} MongoDB row with
 * the on-disk reality under {@code /shared/addons/<name>/<version>/}.
 *
 * <p>Unlike {@link AddonDto} (the public face-bootstrap view) this
 * exposes disabled rows too — Insights is an admin / debug view, so
 * it shows the full picture.
 *
 * <p>Spec: {@code specification/addon-system.md}; backend service:
 * {@code AddonService.listForInsights()}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("addon")
public class AddonInsightDto {

    /** Stable addon name — matches {@code vance-addon.yaml id:}. */
    private String name;

    /**
     * Human-readable label from the addon's {@code VanceAddon} bean,
     * or {@link #name} when no bean was found.
     */
    private String displayName;

    /** Source location: {@code bundled:<id>} or an external URL. */
    private String path;

    /** {@code true} if {@code db.addons.enabled=true}. */
    private boolean enabled;

    /**
     * {@code true} if the entrypoint unpacked the bundle (has a
     * {@code .ready} marker) AND a {@code VanceAddon} Spring bean
     * with the matching id is registered. False when either side
     * is missing — see {@link #status} for the diagnosis.
     */
    private boolean loaded;

    /**
     * {@code true} when the bundle is unpacked on disk
     * ({@code .ready} marker exists), independent of whether Spring
     * actually registered the addon's bean. Used to detect the
     * "unpacked but bean missing" diagnostic case.
     */
    private boolean unpacked;

    /**
     * {@code true} when a {@code VanceAddon} bean is registered for
     * this addon id. Diverges from {@link #unpacked} when something
     * went wrong in Spring (auto-config skipped, bean conflict, …).
     */
    private boolean beanRegistered;

    /**
     * Free-form status text — either from the addon's own
     * {@code VanceAddon.status()} (runtime hint) or synthesised by
     * the aggregator for unmatched cases (e.g. "unpacked but no
     * VanceAddon bean registered"). {@code null} means "all clean".
     */
    private @Nullable String status;

    /**
     * Version string parsed from the on-disk
     * {@code META-INF/vance-addon.yaml}. {@code null} when the addon
     * is in the DB but no unpacked directory exists yet.
     */
    private @Nullable String version;

    /**
     * Configured SHA-256 checksum, format {@code "sha256:<hex>"}, or
     * {@code null} if no checksum was set on the row.
     */
    private @Nullable String checksum;

    /**
     * Verification result of {@link #checksum} against the cached
     * {@code .vab} on disk. {@link ChecksumStatus#NONE} when no
     * checksum is configured.
     */
    private ChecksumStatus checksumStatus;

    /** Mongo {@code createdAt} of the addon row. */
    private @Nullable Instant createdAt;

    /**
     * Modification time of the {@code .ready} marker inside the
     * unpacked addon directory. {@code null} when the addon has not
     * been unpacked yet.
     */
    private @Nullable Instant unpackedAt;
}
