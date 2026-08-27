package de.mhus.vance.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.kit.KitInheritDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/admin/projects}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("projects")
public class ProjectCreateRequest {

    @NotBlank
    @Pattern(regexp = "^[a-z0-9][a-z0-9_-]*$",
            message = "must be lower-case alphanumerics with optional '-' or '_'")
    private String name;

    private @Nullable String title;

    /** Optional — name of the {@code ProjectGroupDocument} this project lives in. */
    private @Nullable String projectGroupId;

    @Builder.Default
    private List<String> teamIds = new ArrayList<>();

    /**
     * Optional — name of an entry in the tenant-wide project-kits
     * catalog (spec: {@code project-kits-catalog.md}). When set, the
     * catalog entry is resolved and the referenced kit is installed
     * into the new project right after creation, in the same request.
     * Unknown names fail the whole call so the operator never gets a
     * half-finished project. {@code null} or blank → no kit install.
     */
    private @Nullable String kitName;

    /**
     * Optional — name of a project of this tenant that is itself a kit source
     * ({@code _vance/kits/manifest.yaml}); its kit is installed into the new
     * project. Mutually exclusive with {@link #kitName}.
     *
     * <p><b>A separate field on purpose, not a {@code project:} url squeezed
     * into {@code kitName}.</b> That field is a <em>catalog key</em>, and a
     * miss on it falls through to an LLM resolver that picks the closest
     * catalog entry — so a mistyped project name would not fail, it would
     * silently install some other kit. Two fields keep "look this up in the
     * curated list" and "install from that project over there" apart, which is
     * also what the two dropdowns in the UI say.
     */
    private @Nullable String kitProject;

    /**
     * Optional — a kit source given by hand: url, and optionally sub-path and
     * branch. For a kit that is neither in the catalog nor authored in this
     * install. Mutually exclusive with {@link #kitName} and
     * {@link #kitProject}.
     *
     * <p>Coordinates only — no token, no vault passphrase. A private
     * repository or a kit shipping credentials needs those, and they belong in
     * the install dialog on the project card, which already has the full form.
     * The create call surfaces such a failure through the
     * {@code X-Vance-Kit-Install-Error} header, so the project exists and the
     * install can be repeated there with what it needs; putting a credential
     * field into project-create to save that one step is not worth a
     * secret-carrying field on this DTO.
     */
    private @Nullable KitInheritDto kitSource;
}
