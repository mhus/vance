package de.mhus.vance.addon.brain.contacts.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A {@code kind: contact-board} document: a curated, ordered list of pinned
 * contacts with a heading and a note each — "who is who" for one topic.
 */
public record ContactBoard(@Nullable String title, @Nullable String description, List<ContactPin> pins) {

    public ContactBoard {
        title = title == null || title.isBlank() ? null : title.strip();
        description = description == null || description.isBlank() ? null : description.strip();
        pins = pins == null ? List.of() : List.copyOf(pins);
    }

    public ContactBoard withPins(List<ContactPin> newPins) {
        return new ContactBoard(title, description, newPins);
    }
}
