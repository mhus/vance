package de.mhus.vance.api.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** The event that was made from a catalog trigger. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class CatalogTriggerCreateResponse {

    /** Name of the event. */
    private String name;

    /** Document path of the event. */
    private String path;

    /** Path of the URL the service has to call, below the brain's host. */
    private String urlPath;

    /**
     * The setting the secret of the event has to be stored under before the service calls (bearer token or
     * signature secret), or {@code null} when the event is open or the server made the secret itself.
     */
    private @Nullable String secretSetting;

    /**
     * Whether the service was told the event's URL, so nothing has to be entered there by hand. Then the
     * secret was made and stored by the server as well.
     */
    private boolean subscribed;
}
