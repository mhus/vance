package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One entry in a tenant's project-kits catalog
 * ({@code _vance/config/project-kits.yaml}). Maps a catalog-local
 * {@code name} (the lookup key shown to Web/Foot/Eddie) to a git source
 * pointing at a kit.
 *
 * <p>See {@code specification/project-kits-catalog.md} §3.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ProjectKitEntry {

    /**
     * Catalog lookup key. Unique within the file. May contain {@code /}
     * as a free string — the catalog code does <b>not</b> parse it as a
     * path. Clients may group visually but must not depend on it.
     */
    private String name;

    /** Display title for UI and CLI. */
    private String title;

    /** One-line description, used as tooltip/subtitle. */
    private @Nullable String description;

    /**
     * Where the kit lives in git. Reuses {@link KitInheritDto} so the
     * project-create flow can pass this straight into the existing
     * {@code kit install} path without translation.
     */
    private KitInheritDto source;
}
