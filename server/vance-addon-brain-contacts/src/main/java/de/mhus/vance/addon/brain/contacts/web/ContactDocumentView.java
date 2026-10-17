package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * What a {@code kind: contact} document renders: its own card or the card
 * a reference resolved to. {@code entry} is set when the shown card is an
 * address-book card (picture, "open in contacts"); {@code dangling} when a
 * reference points nowhere.
 */
@GenerateTypeScript("contacts")
public record ContactDocumentView(
        @Nullable ContactCard card,
        @Nullable ContactSummaryView entry,
        @Nullable String refId,
        boolean dangling) {}
