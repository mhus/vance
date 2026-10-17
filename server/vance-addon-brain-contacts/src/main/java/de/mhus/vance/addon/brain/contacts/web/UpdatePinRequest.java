package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Change a pin: {@code null} = unchanged, {@code ""} = clear (label, note). */
@GenerateTypeScript("contacts")
public record UpdatePinRequest(
        String uuid,
        @Nullable String label,
        @Nullable String note,
        @Nullable Integer position) {}
