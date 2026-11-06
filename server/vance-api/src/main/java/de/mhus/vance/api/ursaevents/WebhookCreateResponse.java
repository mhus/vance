package de.mhus.vance.api.ursaevents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The event that was made, and the one chance to read its token. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("events")
public class WebhookCreateResponse {

    /** Name of the event. */
    private String name;

    /** Document path of the event. */
    private String path;

    /** Path of the URL to call, below the brain's host. */
    private String urlPath;

    /** The setting that holds the token; it is stored encrypted and not shown again. */
    private String tokenSetting;

    /**
     * The bearer token the caller has to send. It is generated here and stored; this response is the only place it
     * is ever shown.
     */
    private String token;
}
