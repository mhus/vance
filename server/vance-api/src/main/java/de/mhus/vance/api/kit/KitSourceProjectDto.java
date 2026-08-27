package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A project of this tenant that is itself a kit source — one carrying
 * {@code _vance/kits/manifest.yaml} — offered as something to install from.
 *
 * <p>What is picked is a <b>kit</b>, and the project is where it happens to
 * live: hence {@link #kitName} first and {@link #projectId} as the qualifier.
 * The two differ often enough for it to matter — a project called
 * {@code kit-dev} authoring a kit called {@code acme-onboarding}.
 *
 * <p>{@code project:<projectId>} is the url to install from; the client does
 * not build it, {@link #sourceUrl} carries it, so there is one place that
 * knows the scheme.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitSourceProjectDto {

    /** Name of the kit, from the authoring manifest. */
    private String kitName;

    private @Nullable String kitDescription;

    private @Nullable String version;

    /** The project holding it — its {@code name}, not its Mongo id. */
    private String projectId;

    /** Display title of that project, when it has one. */
    private @Nullable String projectTitle;

    /** Ready-made {@code project:<name>} url for the install request. */
    private String sourceUrl;
}
