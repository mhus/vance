package de.mhus.vance.addon.brain.contacts.model;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * One contact card — a person, an organisation or a group — with vCard 4.0
 * meaning per field. The on-disk form is YAML ({@code ContactCodec}); this
 * record is what every reader works with.
 *
 * <p>Multi-valued fields are always lists, even with one entry. {@code extras}
 * carries vCard properties the model does not know (keyed by property name),
 * so an import followed by an export loses nothing.
 *
 * @param photo     file name of the picture next to the card
 *                  ({@code <uuid>.jpg}), {@code null} without one
 * @param birthday  vCard {@code BDAY}: {@code yyyy-MM-dd} or {@code --MM-dd}
 *                  without a year; passed through, not interpreted
 * @param members   group members ({@code kind: group} only): UUIDs of other
 *                  cards or URIs ({@code mailto:…}) without a card
 * @param vanceUser login of a Vance user this contact is — a hint, never a right
 */
@Builder(toBuilder = true)
@GenerateTypeScript("contacts")
public record ContactCard(
        ContactKind kind,
        String fullName,
        @Nullable ContactName name,
        List<String> nicknames,
        @Nullable String photo,
        List<ContactValue> emails,
        List<ContactValue> phones,
        List<ContactAddress> addresses,
        @Nullable ContactOrg org,
        @Nullable String title,
        @Nullable String role,
        @Nullable String birthday,
        @Nullable String anniversary,
        List<ContactValue> urls,
        List<ContactValue> impps,
        List<String> languages,
        @Nullable String timezone,
        List<String> categories,
        @Nullable String note,
        List<ContactValue> related,
        List<String> members,
        @Nullable String vanceUser,
        Map<String, Object> extras) {

    public ContactCard {
        if (kind == null) kind = ContactKind.INDIVIDUAL;
        fullName = fullName == null ? "" : fullName.trim();
        if (name != null && name.isEmpty()) name = null;
        if (org != null && org.isEmpty()) org = null;
        nicknames = nonBlank(nicknames);
        photo = blankToNull(photo);
        emails = values(emails);
        phones = values(phones);
        addresses = addresses == null
                ? List.of()
                : addresses.stream().filter(a -> a != null && !a.isEmpty()).toList();
        title = blankToNull(title);
        role = blankToNull(role);
        birthday = blankToNull(birthday);
        anniversary = blankToNull(anniversary);
        urls = values(urls);
        impps = values(impps);
        languages = nonBlank(languages);
        timezone = blankToNull(timezone);
        categories = nonBlank(categories);
        note = note == null || note.isBlank() ? null : note.strip();
        related = values(related);
        members = nonBlank(members);
        vanceUser = blankToNull(vanceUser);
        extras = extras == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    /**
     * The name to show: the full name, else the structured name, else the
     * organisation — never empty for a card that has any of them.
     */
    public String displayName() {
        if (!fullName.isEmpty()) return fullName;
        if (name != null && !name.display().isEmpty()) return name.display();
        if (org != null && org.name() != null) return org.name();
        return "";
    }

    /** The first value of a list ordered by {@code pref} (lowest wins), else the first. */
    public static @Nullable String preferred(List<ContactValue> values) {
        ContactValue best = null;
        for (ContactValue v : values) {
            if (best == null) {
                best = v;
                continue;
            }
            int bp = best.pref() == null ? Integer.MAX_VALUE : best.pref();
            int vp = v.pref() == null ? Integer.MAX_VALUE : v.pref();
            if (vp < bp) best = v;
        }
        return best == null ? null : best.value();
    }

    private static List<ContactValue> values(@Nullable List<ContactValue> in) {
        if (in == null) return List.of();
        return in.stream().filter(v -> v != null && !v.value().isEmpty()).toList();
    }

    private static List<String> nonBlank(@Nullable List<String> in) {
        if (in == null) return List.of();
        return in.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .toList();
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
