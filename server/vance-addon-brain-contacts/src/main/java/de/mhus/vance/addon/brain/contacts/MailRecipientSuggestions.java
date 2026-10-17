package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import de.mhus.vance.api.form.FormSuggestionDto;
import de.mhus.vance.brain.suggestion.SuggestionRequest;
import de.mhus.vance.brain.suggestion.SuggestionSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code mail-recipients}: mail addresses from the address book for the
 * "to" field of the mail share handlers (SMTP, Gmail).
 *
 * <p>A person or organisation suggests each of its addresses (preferred
 * first); a group suggests all its members' preferred addresses at once —
 * the UI inserts them one by one, so the sender sees every recipient before
 * sending. Members without an address are named in the note, not dropped
 * silently. Everything is read through {@link ContactService} with the
 * typing user's rights: nobody gets suggested an address from someone
 * else's private contacts.
 */
@Component
public class MailRecipientSuggestions implements SuggestionSource {

    public static final String NAME = "mail-recipients";

    private final ContactService contactService;

    public MailRecipientSuggestions(ContactService contactService) {
        this.contactService = contactService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<FormSuggestionDto> suggest(SuggestionRequest request) {
        ContactCaller caller =
                ContactCaller.of(request.tenantId(), request.subject(), request.userLogin(), request.projectId());
        List<FormSuggestionDto> out = new ArrayList<>();
        for (ContactEntry e : contactService.search(caller, null, ContactSearch.Criteria.query(request.query()))) {
            if (out.size() >= request.limit()) break;
            ContactCard card = e.card();
            if (card.kind() == ContactKind.GROUP) {
                FormSuggestionDto group = group(caller, e);
                if (group != null) out.add(group);
                continue;
            }
            for (ContactValue mail : byPreference(card.emails())) {
                if (out.size() >= request.limit()) break;
                out.add(new FormSuggestionDto(
                        card.displayName(),
                        mail.value(),
                        List.of(mail.value()),
                        card.kind() == ContactKind.ORG ? "org" : "person",
                        null));
            }
        }
        return out;
    }

    private @Nullable FormSuggestionDto group(ContactCaller caller, ContactEntry group) {
        Set<String> addresses = new LinkedHashSet<>();
        int without = 0;
        for (Map.Entry<String, @Nullable ContactEntry> m :
                contactService.resolveMembers(caller, group).entrySet()) {
            ContactEntry member = m.getValue();
            if (member != null) {
                String mail = ContactCard.preferred(member.card().emails());
                if (mail != null) addresses.add(mail);
                else without++;
            } else if (m.getKey().regionMatches(true, 0, "mailto:", 0, 7)) {
                addresses.add(m.getKey().substring(7));
            } else {
                without++;
            }
        }
        if (addresses.isEmpty()) return null;
        String note = without == 0
                ? null
                : without == 1 ? "1 member without an address" : without + " members without an address";
        String description = addresses.size() == 1 ? "1 address" : addresses.size() + " addresses";
        return new FormSuggestionDto(group.card().displayName(), description, List.copyOf(addresses), "group", note);
    }

    private static List<ContactValue> byPreference(List<ContactValue> values) {
        List<ContactValue> sorted = new ArrayList<>(values);
        sorted.sort((a, b) -> Integer.compare(
                a.pref() == null ? Integer.MAX_VALUE : a.pref(), b.pref() == null ? Integer.MAX_VALUE : b.pref()));
        return sorted;
    }
}
