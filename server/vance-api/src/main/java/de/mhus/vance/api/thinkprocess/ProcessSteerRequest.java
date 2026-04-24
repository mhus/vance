package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code process-steer} — client delivers a user chat message to a
 * think-process in its bound session.
 *
 * <p>v1 only supports plain user chat text. Other steer-message kinds
 * (tool results, external commands) get their own transports once those
 * features exist.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessSteerRequest {

    /** Name of the target process within the session. */
    @NotBlank
    private String processName;

    /** Chat content. */
    @NotBlank
    private String content;

    /** Optional idempotency key for client retries. */
    private @Nullable String idempotencyKey;
}
