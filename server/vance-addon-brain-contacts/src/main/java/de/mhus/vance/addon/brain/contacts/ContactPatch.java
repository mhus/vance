package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies a {@code contact_update} parameter map to a stored card.
 *
 * <p>Semantics: a field that is <em>absent</em> stays as it is; a field that
 * is present replaces the stored value — {@code null}, {@code ""} or
 * {@code []} clear it. Lists are replaced whole, which is why the
 * {@code add…}/{@code remove…} helpers exist: a model adding one phone
 * number should not have to send back every other one (and lose one on the
 * way).
 */
public final class ContactPatch {

    /** Card fields a patch may set. {@code photo} belongs to the service, {@code extras} to import. */
    public static final List<String> EDITABLE = List.of(
            "kind",
            "fullName",
            "name",
            "nicknames",
            "emails",
            "phones",
            "addresses",
            "org",
            "title",
            "role",
            "birthday",
            "anniversary",
            "urls",
            "impps",
            "languages",
            "timezone",
            "categories",
            "note",
            "related",
            "members",
            "vanceUser");

    private ContactPatch() {}

    public static ContactCard apply(ContactCard current, Map<String, Object> params) {
        Map<String, Object> m = ContactCodec.cardToMap(current);
        // cardToMap fills fullName from the display fallback; keep the stored value.
        if (current.fullName().isEmpty()) m.remove("fullName");
        for (String key : EDITABLE) {
            if (!params.containsKey(key)) continue;
            Object v = params.get(key);
            if (v == null || v instanceof String s && s.isBlank() || v instanceof List<?> l && l.isEmpty()) {
                m.remove(key);
            } else {
                m.put(key, v);
            }
        }
        ContactCard card = ContactCodec.cardFromMap(m);

        List<ContactValue> emails = new ArrayList<>(card.emails());
        addValues(emails, ContactCodec.valueList(params.get("addEmails")), false);
        removeValues(emails, ContactCodec.strList(params.get("removeEmails")), false);
        List<ContactValue> phones = new ArrayList<>(card.phones());
        addValues(phones, ContactCodec.valueList(params.get("addPhones")), true);
        removeValues(phones, ContactCodec.strList(params.get("removePhones")), true);
        List<String> categories = new ArrayList<>(card.categories());
        for (String c : ContactCodec.strList(params.get("addCategories"))) {
            if (categories.stream().noneMatch(x -> x.equalsIgnoreCase(c))) categories.add(c);
        }
        List<String> drop = ContactCodec.strList(params.get("removeCategories"));
        categories.removeIf(x -> drop.stream().anyMatch(d -> d.equalsIgnoreCase(x)));

        return card.toBuilder()
                .emails(emails)
                .phones(phones)
                .categories(categories)
                .build();
    }

    /** Whether the map asks for any change at all. */
    public static boolean isEmpty(Map<String, Object> params) {
        for (String key : EDITABLE) if (params.containsKey(key)) return false;
        for (String key : List.of(
                "addEmails", "removeEmails", "addPhones", "removePhones", "addCategories", "removeCategories")) {
            if (params.containsKey(key)) return false;
        }
        return true;
    }

    private static void addValues(List<ContactValue> target, List<ContactValue> add, boolean phone) {
        for (ContactValue v : add) {
            if (target.stream().noneMatch(t -> same(t.value(), v.value(), phone))) target.add(v);
        }
    }

    private static void removeValues(List<ContactValue> target, List<String> remove, boolean phone) {
        target.removeIf(t -> remove.stream().anyMatch(r -> same(t.value(), r, phone)));
    }

    private static boolean same(String a, String b, boolean phone) {
        if (phone) return ContactSearch.normalizePhone(a).equals(ContactSearch.normalizePhone(b));
        return a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }
}
