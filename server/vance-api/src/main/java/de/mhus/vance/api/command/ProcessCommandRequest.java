package de.mhus.vance.api.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * WebSocket {@code process-command} request payload — the {@code //verb}
 * client surface. A generic control-plane function call delivered to a
 * think-process's engine and dispatched on the process lane to the
 * engine-command handler registry.
 *
 * <p>Unlike {@code process-steer}, a command does <b>not</b> feed the
 * LLM: an unknown verb is a defined no-op, a known verb runs its
 * handler. See {@code planning/engine-commands.md} §2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("command")
public class ProcessCommandRequest {

    /** Target think-process (its {@code name} within the session). */
    @NotBlank
    private String processName;

    /**
     * The command verb, optionally namespaced ({@code namespace.verb},
     * e.g. {@code status.set}). The dispatcher routes to the handler
     * registered for this exact string.
     */
    @NotBlank
    private String command;

    /** Free-form command arguments; {@code null} is treated as empty. */
    private @Nullable Map<String, Object> params;
}
