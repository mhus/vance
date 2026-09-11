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
 * Turns a project into a kit source <em>from scratch</em> — the third way an
 * authoring manifest can come to exist, next to {@code writeManifest} at
 * install time and promote from an install record
 * (specification/public/kits.md §4.3).
 *
 * <p>For a project that never installed the kit it is about to develop:
 * the author writes the kit's content as ordinary project documents, then
 * declares with this request that those documents and settings are the
 * kit. The service verifies every listed artefact exists, computes the
 * encrypted-secrets flag from the settings' actual types and writes the
 * manifest plus a starter descriptor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitAuthoringRequestDto {

    private String projectId;

    /** Logical kit name — becomes the top-layer identity of the kit. */
    private String name;

    private String description;

    private @Nullable String version;

    /** Document paths (relative to the project document root) the kit ships. */
    @Builder.Default
    private List<String> documents = new ArrayList<>();

    /** Project-scoped setting keys the kit ships. */
    @Builder.Default
    private List<String> settings = new ArrayList<>();

    /**
     * Git remote the kit lives at — the default target of {@code export}.
     * Required because the manifest's origin is load-bearing: a manifest
     * without it does not parse, so a project would stop being a kit source
     * the moment it became one.
     */
    private String originUrl;

    private @Nullable String originBranch;

    private @Nullable String originPath;

    /**
     * Source urls of kits this one inherits from — each entry is an
     * {@code inherits:} line of the descriptor (git url or
     * {@code project:<name>}), verbatim.
     */
    @Builder.Default
    private List<String> inherits = new ArrayList<>();
}
