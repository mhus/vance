/**
 * Contacts addon: a typed address book on top of documents. Cards live as
 * {@code kind: contact} YAML under {@code _vance/contacts/} in three scopes
 * (user hub, project, {@code _tenant}); {@link de.mhus.vance.addon.brain.contacts.ContactService}
 * is the only reader and writer the rest of the system talks to.
 */
@NullMarked
package de.mhus.vance.addon.brain.contacts;

import org.jspecify.annotations.NullMarked;
