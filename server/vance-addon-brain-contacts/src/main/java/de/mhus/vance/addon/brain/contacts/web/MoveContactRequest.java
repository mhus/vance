package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Move a card from one scope to another. */
@GenerateTypeScript("contacts")
public record MoveContactRequest(@Nullable ContactScope from, ContactScope to) {}
