package de.mhus.vance.addon.brain.contacts.model;

import org.jspecify.annotations.Nullable;

/**
 * The body of a {@code kind: contact} document: either a card of its own or a
 * reference to a card in the address book ({@code _vance/contacts/}).
 */
public sealed interface ContactBody {

    /** The document carries the card itself. */
    record Inline(ContactCard card) implements ContactBody {}

    /**
     * The document points at an address-book card, bound late.
     *
     * @param scope where to look; {@code null} = the read cascade
     */
    record Ref(String uuid, @Nullable ContactScope scope) implements ContactBody {}
}
