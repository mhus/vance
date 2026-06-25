package de.mhus.vance.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One participant in a multi-user session roster — see
 * {@code planning/multi-user-sessions.md} §7.
 *
 * <p>{@code editorId} disambiguates concurrent tabs/devices of the
 * same user (one entry per WS connection, not per user).
 * {@code displayName} is the captured-at-register-time label; clients
 * fall back to {@code userId} when it's {@code null}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionParticipantDto {

    private String editorId;
    private String userId;
    private @Nullable String displayName;
}
