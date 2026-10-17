package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** The moved card and the groups that can no longer resolve it. */
@GenerateTypeScript("contacts")
public record MoveContactView(ContactView contact, List<ContactSummaryView> strandedGroups) {}
