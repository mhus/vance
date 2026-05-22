package de.mhus.vance.api.eddie;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the {@code switch-to} server-push frame — tells the
 * connected client to drop the current WebSocket and open a fresh one
 * bound to {@link #targetSessionId}. The client keeps the previous
 * {@code sessionId} in local state so it can switch back later (e.g.
 * via a {@code /hub} command).
 *
 * <p>Used by Eddie's {@code MEDIATE} action to hand the user over to a
 * worker project's Arthur for a direct conversation. The wire frame is
 * a generic session-switch primitive — Eddie's flow is one consumer;
 * future flows (project-tabs, peer-hub jumps) can reuse it without
 * adding new frame types.
 *
 * <p>No server-side state is persisted for the switch — the client
 * owns the back-stack. A Brain restart therefore can't leave anyone
 * stranded: the worker session is just a regular session whose
 * lifecycle is unrelated to whether someone "switched to" it.
 *
 * <p>Spec: {@code specification/eddie-engine.md} §8.5,
 * {@code specification/engine-message-routing.md} §4.1.2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("eddie")
public class SwitchToNotification {

    /** Session id the client should bind its WebSocket to. */
    private String targetSessionId = "";

    /** Worker project (for UI labelling — banner / status line). */
    private @Nullable String targetProjectId;

    /** Worker process name, e.g. {@code "arthur"} (for UI labelling). */
    private @Nullable String targetProcessName;

    /**
     * Optional short sentence Eddie says immediately before the
     * switch. Foot may render it as a final terminal line; web may
     * surface it inside a banner. {@code null} for a silent switch.
     */
    private @Nullable String voiceAnnouncement;
}
