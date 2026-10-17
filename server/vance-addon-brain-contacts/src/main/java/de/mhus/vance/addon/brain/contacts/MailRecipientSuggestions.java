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
 *
 * <p>Only plain addresses are suggested: a stored value is trimmed, a
 * {@code mailto:} prefix and any {@code ?query} are cut off, and what remains
 * must hold exactly one {@code @} and no whitespace, comma, semicolon or
 * angle bracket. Anything else ({@code "Anna <a@b>"}, a list, a bare
 * {@code mailto:?subject=}) is not an address the mail handlers can send to
 * and is skipped.
 */
@Component
public class MailRecipientSuggestions implements SuggestionSource {

    public static final String NAME = "mail-recipients";

    private static final String MAILTO = "mailto:";

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
            Set<String> seen = new LinkedHashSet<>();
            for (ContactValue mail : byPreference(card.emails())) {
                if (out.size() >= request.limit()) break;
                String address = address(mail.value());
                if (address == null || !seen.add(address)) continue;
                out.add(new FormSuggestionDto(
                        card.displayName(),
                        address,
                        List.of(address),
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
            String mail = member != null
                    ? firstAddress(member.card().emails())
                    : m.getKey().regionMatches(true, 0, MAILTO, 0, MAILTO.length()) ? address(m.getKey()) : null;
            if (mail != null) addresses.add(mail);
            else without++;
        }
        if (addresses.isEmpty()) return null;
        String note = without == 0
                ? null
                : without == 1 ? "1 member without an address" : without + " members without an address";
        String description = addresses.size() == 1 ? "1 address" : addresses.size() + " addresses";
        return new FormSuggestionDto(group.card().displayName(), description, List.copyOf(addresses), "group", note);
    }

    /** The member's preferred address that is a plain address, else the next one by preference. */
    private static @Nullable String firstAddress(List<ContactValue> values) {
        for (ContactValue v : byPreference(values)) {
            String address = address(v.value());
            if (address != null) return address;
        }
        return null;
    }

    /**
     * The plain address in {@code raw}, or {@code null} when there is none:
     * trims, cuts a {@code mailto:} prefix and a {@code ?query}, then demands
     * exactly one {@code @} with text on both sides and none of whitespace,
     * comma, semicolon or angle brackets.
     */
    static @Nullable String address(@Nullable String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (value.regionMatches(true, 0, MAILTO, 0, MAILTO.length())) {
            value = value.substring(MAILTO.length());
        }
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == ',' || c == ';' || c == '<' || c == '>') return null;
        }
        return value;
    }

    private static List<ContactValue> byPreference(List<ContactValue> values) {
        List<ContactValue> sorted = new ArrayList<>(values);
        sorted.sort((a, b) -> Integer.compare(
                a.pref() == null ? Integer.MAX_VALUE : a.pref(), b.pref() == null ? Integer.MAX_VALUE : b.pref()));
        return sorted;
    }
}
