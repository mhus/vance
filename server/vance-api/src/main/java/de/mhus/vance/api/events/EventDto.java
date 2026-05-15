package de.mhus.vance.api.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Full event document for the Web-UI editor — parsed identity fields
 * plus the raw YAML body. Analog to {@code SchedulerDto} /
 * {@code HactarWorkflowDto}.
 *
 * <p>Bearer tokens are <strong>never</strong> rendered into this DTO
 * — the {@code authConfigured} flag tells the editor whether auth is
 * set without leaking the secret.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("events")
public class EventDto {

    /** Event name — derived from the document path, not stored in the YAML body. */
    private String name;

    /** Verbatim YAML body. */
    private String yaml;

    /** Which cascade tier provides this event. */
    private EventSource source;

    // ─── Parsed convenience fields (mirror of YAML for the UI) ───

    private @Nullable String description;

    /** Workflow name this event spawns when triggered. */
    private @Nullable String workflow;

    /** {@code true} unless the YAML explicitly sets {@code enabled: false}. */
    private boolean enabled;

    private @Nullable List<String> methods;

    /** {@code true} when an {@code auth.token:} or {@code auth.tokenSetting:} is set. */
    private boolean authConfigured;

    /**
     * {@code "bearer"} or {@code "none"}. Convenience for the UI to
     * label the auth status without inspecting the YAML.
     */
    private @Nullable String authType;

    private @Nullable Map<String, Object> params;
    private @Nullable String runAs;
    private @Nullable List<String> tags;
}
