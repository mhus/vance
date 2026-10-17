package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** The LLM power switch of one user — a row of {@link LlmPowerStatusDto#getUsers()}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class LlmPowerUserDto {

    /** Login of the user. */
    private String userId = "";

    /** Display name, {@code null} when the user has none or no longer exists. */
    private @Nullable String title;

    /** {@code true} for service accounts ({@code _}-prefixed logins). */
    private boolean serviceAccount;

    /** {@code false} when the user no longer exists but its switch is still off. */
    private boolean known;

    /** The user's own switch, as stored. */
    private boolean enabled;

    /** The user's switch as the answering pod currently enforces it. */
    private boolean effectiveEnabled;

    /** When the switch was turned off; {@code null} while it is on. */
    private @Nullable Instant since;

    /** Who turned it off, as recorded; {@code null} while it is on. */
    private @Nullable String note;
}
