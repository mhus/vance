package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One row of the address-book list. */
@GenerateTypeScript("contacts")
public record ContactSummaryView(
        String id,
        ContactScope scope,
        String projectId,
        ContactKind kind,
        String displayName,
        @Nullable String org,
        @Nullable String title,
        @Nullable String email,
        @Nullable String phone,
        List<String> categories,
        boolean hasPhoto,
        @Nullable Long photoVersion) {

    public static ContactSummaryView of(ContactEntry e) {
        ContactCard c = e.card();
        return new ContactSummaryView(
                e.id(),
                e.scope(),
                e.projectId(),
                c.kind(),
                c.displayName(),
                c.org() == null ? null : c.org().name(),
                c.title(),
                ContactCard.preferred(c.emails()),
                ContactCard.preferred(c.phones()),
                c.categories(),
                e.photoPath() != null,
                e.photoVersion());
    }
}
