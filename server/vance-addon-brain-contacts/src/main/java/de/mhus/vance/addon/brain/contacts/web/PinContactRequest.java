package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Pin a contact; {@code position} 0-based, {@code null} = at the end. */
@GenerateTypeScript("contacts")
public record PinContactRequest(
        String uuid,
        @Nullable ContactScope scope,
        @Nullable String label,
        @Nullable String note,
        @Nullable Integer position) {}
