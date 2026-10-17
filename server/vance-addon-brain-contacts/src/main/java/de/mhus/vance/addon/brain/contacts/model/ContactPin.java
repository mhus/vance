package de.mhus.vance.addon.brain.contacts.model;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * One contact pinned to a {@code contact-board} — a reference into the
 * address book, never a copy, plus what the board says about it.
 *
 * @param uuid  the contact id
 * @param scope where the contact was found when pinned; {@code null} = the
 *              reader's cascade
 * @param label heading above the card ("Ansprechpartnerin Einkauf")
 * @param note  Markdown below the card
 */
@GenerateTypeScript("contacts")
public record ContactPin(
        String uuid,
        @Nullable ContactScope scope,
        @Nullable String label,
        @Nullable String note) {

    public ContactPin {
        label = label == null || label.isBlank() ? null : label.strip();
        note = note == null || note.isBlank() ? null : note.strip();
    }

    public ContactPin withLabel(@Nullable String newLabel) {
        return new ContactPin(uuid, scope, newLabel, note);
    }

    public ContactPin withNote(@Nullable String newNote) {
        return new ContactPin(uuid, scope, label, newNote);
    }
}
