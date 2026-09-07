package de.mhus.vance.api.inbox;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What a compose recipient is: a single user, or a team the message fans out
 * to. The recipient search lists both under one query, and the kind decides
 * how the send addresses them — {@code assignedToUserId} for a user,
 * {@code teamName} for the fan-out.
 */
@GenerateTypeScript("inbox")
public enum InboxRecipientKind {
    USER,
    TEAM
}
