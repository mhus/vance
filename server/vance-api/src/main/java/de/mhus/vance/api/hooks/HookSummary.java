package de.mhus.vance.api.hooks;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * List-view projection of a hook — fields the UI list and the
 * {@code hook_list} agent tool need to render an item without loading
 * the YAML body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("hooks")
public class HookSummary {

    private String name;
    private String event;
    private HookSource source;

    /**
     * Which {@code TriggerAction} variant this hook fires —
     * {@code "recipe"} / {@code "script"} / {@code "workflow"}.
     */
    private String actionType;

    private boolean enabled;
    private @Nullable String description;
    private @Nullable List<String> tags;

    /** Timestamp of the most recent run, regardless of outcome. */
    private @Nullable Instant lastRunAt;

    /** Type of the most recent terminal event, e.g. {@code "COMPLETED"}. */
    private @Nullable String lastRunType;
}
