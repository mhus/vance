package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * A search answer: the matching rows, the caller's scopes (for the filter
 * chips and the "create in" choice), the default write scope, and the card
 * files that did not parse — so an empty list is never a silent lie.
 */
@GenerateTypeScript("contacts")
public record ContactListView(
        List<ContactSummaryView> entries,
        List<ContactScopeView> scopes,
        ContactScope defaultScope,
        List<String> broken) {}
