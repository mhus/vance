package de.mhus.vance.api.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Make an event from a trigger of the service catalog and point it at a workflow. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class CatalogTriggerCreateRequest {

    /** Catalog entry, e.g. {@code github}. */
    private String service;

    /** Trigger of that entry, e.g. {@code push}. */
    private String trigger;

    /** The workflow the event starts. */
    private String workflow;

    /** Name of the event; {@code <service>-<trigger>} when blank. */
    private @Nullable String name;

    /** What the trigger asked for ({@code ${input.<name>}}), by name. */
    private @Nullable Map<String, String> inputs;
}
