package de.mhus.vance.api.eddie;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the {@code mediate-handover} server notification — see
 * {@code specification/eddie-engine.md} §8.5. Eddie is asking the
 * connected client to {@code session-rebind} from her hub session to
 * the worker session named here.
 *
 * <p>The client follows up with a {@code session-resume} frame
 * carrying {@link #targetSessionId}; the server handles the bind like
 * a regular user-initiated resume (with the same JWT / profile the
 * client originally connected with). Only profiles whose
 * {@code canMediate} capability is true (foot, web — not mobile) get
 * this frame; Eddie skips the {@code MEDIATE} action otherwise.
 *
 * <p>{@code voiceAnnouncement} is what Eddie said just before the
 * handover — the foot client renders it as a final message before
 * the rebind, the web client may show it as a banner during the
 * direct-conversation view.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("eddie")
public class MediateHandoverNotification {

    /** Eddie's process id — for the return path. */
    private String eddieProcessId = "";

    /** Eddie's session id — what the client rebinds back to on /hub. */
    private String eddieSessionId = "";

    /** The worker session the client is being handed over to. */
    private String targetSessionId = "";

    /** The worker project for UI labelling. */
    private @Nullable String targetProjectId;

    /** Worker process name (e.g. {@code "arthur"}) for UI labelling. */
    private @Nullable String targetProcessName;

    /** Optional voice line Eddie said before the handover. */
    private @Nullable String voiceAnnouncement;
}
