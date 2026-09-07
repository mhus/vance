package de.mhus.vance.api.inbox;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry of the compose dialog's recipient search: a user the caller may
 * deliver an inbox item to, or one of the caller's teams.
 *
 * <p>{@code name} is the address — the value {@code assignedToUserId} takes
 * for a user and {@code teamName} for a team. {@code displayName} is the
 * ready-to-render label (the title with the name, or just the name), because
 * two colleagues called "Mara" have to be told apart in a picker, and every
 * client duplicating that fallback would drift.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("inbox")
public class InboxRecipientDto {

    private InboxRecipientKind kind;

    private String name;

    private String displayName;
}
