package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import org.jspecify.annotations.Nullable;

/**
 * One address-book card as the service knows it: the parsed card plus where
 * it lives.
 *
 * @param id         the card's UUID (= file stem)
 * @param scope      the scope it was found in
 * @param projectId  the project backing that scope
 * @param documentId Mongo id of the card document — internal, never on the wire
 * @param photoPath  path of the picture document when one exists, else {@code null}
 * @param photoVersion version of the picture document — a cache key for the
 *                   client, so a replaced picture is fetched anew
 */
public record ContactEntry(
        String id,
        ContactScope scope,
        String projectId,
        String documentId,
        ContactCard card,
        @Nullable String photoPath,
        @Nullable Long photoVersion) {

    public String path() {
        return ContactIds.cardPath(id);
    }

    public ContactEntry withScope(ContactScope newScope) {
        return new ContactEntry(id, newScope, projectId, documentId, card, photoPath, photoVersion);
    }
}
