package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One card with everything the detail view shows: where it lives, the
 * resolved group members (groups only) and the groups that list it.
 */
@GenerateTypeScript("contacts")
public record ContactView(
        String id,
        ContactScope scope,
        String projectId,
        ContactCard card,
        boolean hasPhoto,
        @Nullable Long photoVersion,
        List<ContactMemberView> members,
        List<ContactSummaryView> groups) {}
