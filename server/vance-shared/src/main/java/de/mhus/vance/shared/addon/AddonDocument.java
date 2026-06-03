package de.mhus.vance.shared.addon;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent addon entry — the system-wide source of truth for which
 * addons exist and whether they should be active. There are two
 * conceptual origins:
 *
 * <ul>
 *   <li>Bundled in the container image (created by an idempotent
 *       seeding step on first boot — the bundled {@code .vab}s are
 *       discovered by the Docker entrypoint and ensured here).</li>
 *   <li>Added by an admin pointing at an external {@code .vab} URL —
 *       the brain caches the fetched bundle and verifies the
 *       checksum if one is configured.</li>
 * </ul>
 *
 * <p>{@code enabled=false} hides an addon from the
 * {@code GET /face/addons} listing without deleting the row, so an
 * admin can later flip it back on without re-providing the path.
 * Bundled addons can be disabled the same way.
 *
 * <p>Spec: {@code specification/addon-system.md}.
 */
@Document(collection = "addons")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddonDocument {

    @Id
    private @Nullable String id;

    /**
     * Stable addon name — matches the {@code id:} field in the
     * bundle's {@code META-INF/vance-addon.yaml}. Used as the
     * directory name under {@code /shared/addons/<name>/<version>/}
     * and as the URL segment in {@code /addons/<name>/}.
     */
    @Indexed(unique = true)
    private String name = "";

    /**
     * Where to find the {@code .vab}. For bundled addons this is a
     * marker like {@code bundled:slideshow}; for admin-installed
     * addons it's the source URL (https / git+https / file). The
     * brain entrypoint and the future install service both consult
     * this field.
     */
    private String path = "";

    /**
     * {@code false} hides the row from {@code GET /face/addons} and
     * prevents the entrypoint from unpacking it. Default {@code true}.
     */
    private boolean enabled = true;

    @CreatedDate
    private @Nullable Instant createdAt;
}
