package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Create (scope = target, {@code null} = default) or replace a card (scope =
 * where it lives). The card is taken whole; the picture field is ignored —
 * pictures have their own route.
 */
@GenerateTypeScript("contacts")
public record ContactWriteRequest(
        ContactCard card, @Nullable ContactScope scope) {}
