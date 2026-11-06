package de.mhus.vance.api.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** A trigger of a service: the template an event (webhook) or a scheduler (poll) is made from. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class ServiceCatalogTriggerDto {

    private String name;

    private String description;

    /** {@code webhook} or {@code poll}. */
    private String mode;

    /** Whether the service registers the event URL itself when the trigger is switched on. */
    private boolean subscribes;

    /** JSON Schema of what arrives, for a workflow to start its parameters from. */
    private @Nullable Map<String, Object> payloadSchema;
}
