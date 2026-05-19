package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Follow-up step the Web-UI / chat-agent should surface after a
 * successful template apply.
 *
 * <p>Today only {@code oauth-connect} is defined — links to the
 * tenant's Connected Accounts page for the named provider so the
 * user can authorise the app.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ToolTemplatePostInstallDto {

    /** {@code oauth-connect} for now. */
    private String kind;

    /** OAuth provider id (when {@link #kind} = {@code oauth-connect}). */
    private @Nullable String provider;

    /** Human-readable instruction line for the UI / agent to display verbatim. */
    private @Nullable String message;
}
