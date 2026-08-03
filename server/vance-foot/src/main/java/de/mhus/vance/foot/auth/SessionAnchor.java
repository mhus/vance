package de.mhus.vance.foot.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * Contents of {@code .vancetope/session.yaml} — the "last session" anchor for
 * a working directory. Written whenever foot bootstraps into a session and
 * read back by {@code -c} / {@code --continue} to resume exactly that session.
 *
 * <p>Per directory (= per project, like the sibling {@code project.eddie.yaml} /
 * {@code access.yaml}). It holds no secret — just the id of the session last
 * entered here — so it is not treated as a credential.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionAnchor {

    /** Id of the session last bootstrapped from this directory. */
    private @Nullable String sessionId;

    /** Project the session belongs to — informational, aids display. */
    private @Nullable String projectId;

    /** Unix-millis of the last update; newest bootstrap wins. */
    private @Nullable Long updatedAt;
}
