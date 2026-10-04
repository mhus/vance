package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * The working side of a Trillian pair: the unattended user-loop process
 * running as the minted {@code _trillian-*} service account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class TrillianWorkerInsightsDto {

    /**
     * Service-account name the loop runs as ({@code _trillian-<nature>-<n>}).
     * {@code null} when the account is gone — cosmetic, the state view must
     * still answer.
     */
    private @Nullable String accountId;

    /** Display name of the account (its {@code title}); rename in the user editor. */
    private @Nullable String accountTitle;

    /** Business id of the headless user-session ({@code sess_...}). */
    private @Nullable String sessionId;

    /** Mongo id of the user-loop process — the addressing key of the loop itself. */
    private String processId;

    private String processName;

    private ThinkProcessStatus status;

    /** Depth of the loop's pending inbox (task requests plus results to report). */
    private long pendingInbox;

    /** Free-form loop attributes ({@code //trillian attr}); values stringified. */
    private Map<String, String> attributes;
}
