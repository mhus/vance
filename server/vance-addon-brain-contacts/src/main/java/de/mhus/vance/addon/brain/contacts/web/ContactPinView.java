package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.ContactBoardService;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * One pin as the reader sees it. {@code available=false}: the contact is
 * deleted or not visible to this reader — heading and note still show.
 */
@GenerateTypeScript("contacts")
public record ContactPinView(
        String uuid,
        @Nullable ContactScope scope,
        @Nullable String label,
        @Nullable String note,
        boolean available,
        @Nullable ContactSummaryView contact,
        @Nullable ContactCard card) {

    public static ContactPinView of(ContactBoardService.ResolvedPin p) {
        return new ContactPinView(
                p.pin().uuid(),
                p.pin().scope(),
                p.pin().label(),
                p.pin().note(),
                p.entry() != null,
                p.entry() == null ? null : ContactSummaryView.of(p.entry()),
                p.entry() == null ? null : p.entry().card());
    }
}
