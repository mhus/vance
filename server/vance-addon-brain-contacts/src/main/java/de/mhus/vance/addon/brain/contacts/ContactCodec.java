package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactAddress;
import de.mhus.vance.addon.brain.contacts.model.ContactBody;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactName;
import de.mhus.vance.addon.brain.contacts.model.ContactOrg;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.KindHeaderCodec;
import de.mhus.vance.shared.document.kind.validate.Finding;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Parser and serialiser for {@code kind: contact} documents.
 *
 * <p>On disk:
 * <pre>
 * $meta:
 *   kind: contact
 * contact:            # either a card …
 *   kind: individual
 *   fullName: Anna Berger
 *   emails:
 *     - value: anna@example.org
 *       types: [work]
 * ref:                # … or a reference into the address book
 *   uuid: 3f0c…
 *   scope: tenant     # optional
 * </pre>
 *
 * <p>Reading is lenient (a bare string where a value object is expected is
 * taken as its {@code value}; unquoted YAML dates come back as text), because
 * admins may edit these files by hand. {@link #validate} reports what the
 * lenient read would drop or misread, with a path per finding.
 *
 * <p>{@link #cardFromMap}/{@link #cardToMap} are public: the tools take the
 * same field names as parameters, so the model learns one grammar.
 */
public final class ContactCodec {

    public static final String KIND = "contact";

    static final String KEY_CARD = "contact";
    static final String KEY_REF = "ref";

    /** Every field name of a card, in the order they are written. */
    public static final List<String> CARD_FIELDS = List.of(
            "kind",
            "fullName",
            "name",
            "nicknames",
            "photo",
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
            "vanceUser",
            "extras");

    private static final Set<String> VALUE_LIST_FIELDS = Set.of("emails", "phones", "urls", "impps", "related");

    private static final Pattern DATE = Pattern.compile("^(\\d{4}|--)-?\\d{2}-\\d{2}$|^\\d{4}-\\d{2}$|^\\d{4}$");

    private ContactCodec() {}

    // ── Document level ────────────────────────────────────────────

    public static ContactBody parse(String body) {
        Map<String, Object> top = KindHeaderCodec.parseYamlBody(body);
        Object ref = top.get(KEY_REF);
        Object card = top.get(KEY_CARD);
        if (card instanceof Map<?, ?> cardMap) {
            return new ContactBody.Inline(cardFromMap(stringKeys(cardMap)));
        }
        if (ref instanceof Map<?, ?> refMap) {
            Map<String, Object> m = stringKeys(refMap);
            String uuid = ContactIds.normalize(str(m.get("uuid")));
            if (uuid == null) throw new KindCodecException("contact ref.uuid is not a UUID");
            ContactScope scope = scopeOrNull(str(m.get("scope")));
            return new ContactBody.Ref(uuid, scope);
        }
        throw new KindCodecException("A contact document needs a 'contact:' card or a 'ref:' block");
    }

    /** Parse a body that must be a card (the address-book files). */
    public static ContactCard parseCard(String body) {
        ContactBody parsed = parse(body);
        if (parsed instanceof ContactBody.Inline inline) return inline.card();
        throw new KindCodecException("Expected a contact card, found a reference");
    }

    public static String serialize(ContactCard card) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(KEY_CARD, cardToMap(card));
        return KindHeaderCodec.dumpYamlBody(KIND, body);
    }

    public static String serializeRef(String uuid, @Nullable ContactScope scope) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("uuid", ContactIds.require(uuid));
        if (scope != null) ref.put("scope", scope.wire());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(KEY_REF, ref);
        return KindHeaderCodec.dumpYamlBody(KIND, body);
    }

    // ── Card ⇄ map ────────────────────────────────────────────────

    public static ContactCard cardFromMap(Map<String, Object> m) {
        ContactCard.ContactCardBuilder b = ContactCard.builder()
                .kind(ContactKind.fromWire(str(m.get("kind"))))
                .fullName(strOrEmpty(m.get("fullName")))
                .name(nameFromObject(m.get("name")))
                .nicknames(strList(m.get("nicknames")))
                .photo(str(m.get("photo")))
                .emails(valueList(m.get("emails")))
                .phones(valueList(m.get("phones")))
                .addresses(addressList(m.get("addresses")))
                .org(orgFromObject(m.get("org")))
                .title(str(m.get("title")))
                .role(str(m.get("role")))
                .birthday(str(m.get("birthday")))
                .anniversary(str(m.get("anniversary")))
                .urls(valueList(m.get("urls")))
                .impps(valueList(m.get("impps")))
                .languages(strList(m.get("languages")))
                .timezone(str(m.get("timezone")))
                .categories(strList(m.get("categories")))
                .note(str(m.get("note")))
                .related(valueList(m.get("related")))
                .members(memberList(m.get("members")))
                .vanceUser(str(m.get("vanceUser")))
                .extras(m.get("extras") instanceof Map<?, ?> x ? stringKeys(x) : Map.of());
        return b.build();
    }

    public static Map<String, Object> cardToMap(ContactCard c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", c.kind().wire());
        m.put("fullName", c.fullName().isEmpty() ? c.displayName() : c.fullName());
        if (c.name() != null) m.put("name", nameToMap(c.name()));
        putList(m, "nicknames", c.nicknames());
        putOpt(m, "photo", c.photo());
        putValues(m, "emails", c.emails());
        putValues(m, "phones", c.phones());
        if (!c.addresses().isEmpty()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (ContactAddress a : c.addresses()) out.add(addressToMap(a));
            m.put("addresses", out);
        }
        if (c.org() != null) {
            Map<String, Object> org = new LinkedHashMap<>();
            putOpt(org, "name", c.org().name());
            putList(org, "units", c.org().units());
            putOpt(org, "ref", c.org().ref());
            m.put("org", org);
        }
        putOpt(m, "title", c.title());
        putOpt(m, "role", c.role());
        putOpt(m, "birthday", c.birthday());
        putOpt(m, "anniversary", c.anniversary());
        putValues(m, "urls", c.urls());
        putValues(m, "impps", c.impps());
        putList(m, "languages", c.languages());
        putOpt(m, "timezone", c.timezone());
        putList(m, "categories", c.categories());
        putOpt(m, "note", c.note());
        putValues(m, "related", c.related());
        putList(m, "members", c.members());
        putOpt(m, "vanceUser", c.vanceUser());
        if (!c.extras().isEmpty()) m.put("extras", new LinkedHashMap<>(c.extras()));
        return m;
    }

    public static Map<String, Object> valueToMap(ContactValue v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", v.value());
        if (!v.types().isEmpty()) m.put("types", new ArrayList<>(v.types()));
        if (v.pref() != null) m.put("pref", v.pref());
        return m;
    }

    public static Map<String, Object> addressToMap(ContactAddress a) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!a.types().isEmpty()) m.put("types", new ArrayList<>(a.types()));
        if (a.pref() != null) m.put("pref", a.pref());
        putOpt(m, "poBox", a.poBox());
        putOpt(m, "extended", a.extended());
        putOpt(m, "street", a.street());
        putOpt(m, "locality", a.locality());
        putOpt(m, "region", a.region());
        putOpt(m, "postalCode", a.postalCode());
        putOpt(m, "country", a.country());
        putOpt(m, "label", a.label());
        return m;
    }

    public static Map<String, Object> nameToMap(ContactName n) {
        Map<String, Object> m = new LinkedHashMap<>();
        putOpt(m, "family", n.family());
        putOpt(m, "given", n.given());
        putList(m, "additional", n.additional());
        putList(m, "prefixes", n.prefixes());
        putList(m, "suffixes", n.suffixes());
        return m;
    }

    // ── Lenient element readers (shared with the tools) ───────────

    public static @Nullable ContactName nameFromObject(@Nullable Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> m = stringKeys(map);
            ContactName n = new ContactName(
                    str(m.get("family")),
                    str(m.get("given")),
                    strList(m.get("additional")),
                    strList(m.get("prefixes")),
                    strList(m.get("suffixes")));
            return n.isEmpty() ? null : n;
        }
        return null;
    }

    public static @Nullable ContactOrg orgFromObject(@Nullable Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> m = stringKeys(map);
            ContactOrg org = new ContactOrg(str(m.get("name")), strList(m.get("units")), str(m.get("ref")));
            return org.isEmpty() ? null : org;
        }
        // A bare string is the organisation name — the common hand-written form.
        String name = str(raw);
        return name == null ? null : new ContactOrg(name, List.of(), null);
    }

    public static List<ContactValue> valueList(@Nullable Object raw) {
        List<ContactValue> out = new ArrayList<>();
        for (Object o : asList(raw)) {
            ContactValue v = valueFromObject(o);
            if (v != null) out.add(v);
        }
        return out;
    }

    public static @Nullable ContactValue valueFromObject(@Nullable Object o) {
        if (o instanceof Map<?, ?> map) {
            Map<String, Object> m = stringKeys(map);
            String value = str(m.get("value"));
            if (value == null) return null;
            return new ContactValue(value, strList(m.get("types")), intOrNull(m.get("pref")));
        }
        String value = str(o);
        return value == null ? null : new ContactValue(value, List.of(), null);
    }

    public static List<ContactAddress> addressList(@Nullable Object raw) {
        List<ContactAddress> out = new ArrayList<>();
        for (Object o : asList(raw)) {
            ContactAddress a = addressFromObject(o);
            if (a != null) out.add(a);
        }
        return out;
    }

    public static @Nullable ContactAddress addressFromObject(@Nullable Object o) {
        if (o instanceof Map<?, ?> map) {
            Map<String, Object> m = stringKeys(map);
            ContactAddress a = new ContactAddress(
                    strList(m.get("types")),
                    intOrNull(m.get("pref")),
                    str(m.get("poBox")),
                    str(m.get("extended")),
                    str(m.get("street")),
                    str(m.get("locality")),
                    str(m.get("region")),
                    str(m.get("postalCode")),
                    str(m.get("country")),
                    str(m.get("label")));
            return a.isEmpty() ? null : a;
        }
        // A bare string is a free-form label.
        String label = str(o);
        return label == null
                ? null
                : new ContactAddress(List.of(), null, null, null, null, null, null, null, null, label);
    }

    /** Members: UUIDs are normalised, URIs kept, anything else kept verbatim (validate reports it). */
    public static List<String> memberList(@Nullable Object raw) {
        List<String> out = new ArrayList<>();
        for (String s : strList(raw)) {
            String id = ContactIds.normalize(s);
            String member = id != null ? id : s;
            if (!out.contains(member)) out.add(member);
        }
        return out;
    }

    // ── Validation ────────────────────────────────────────────────

    /**
     * Findings for a raw body. Advisory: a finding never blocks a write, but
     * it names what the lenient reader would silently drop or misread.
     */
    public static List<Finding> validate(String body, String location) {
        List<Finding> out = new ArrayList<>();
        Map<String, Object> top;
        try {
            top = KindHeaderCodec.parseYamlBody(body);
        } catch (KindCodecException e) {
            out.add(Finding.error(location, "contact-parse", "Parse error: " + e.getMessage()));
            return out;
        }
        Object card = top.get(KEY_CARD);
        Object ref = top.get(KEY_REF);
        if (card != null && ref != null) {
            out.add(Finding.error(
                    location, "contact-both", "Use either 'contact:' or 'ref:', not both — the card wins"));
        }
        if (card == null && ref == null) {
            out.add(Finding.error(location, "contact-empty", "Neither a 'contact:' card nor a 'ref:' block"));
            return out;
        }
        if (card != null) {
            if (card instanceof Map<?, ?> cm) {
                validateCard(stringKeys(cm), location + ":contact", out);
            } else {
                out.add(Finding.error(location + ":contact", "contact-shape", "'contact' must be a mapping"));
            }
        } else if (ref instanceof Map<?, ?> rm) {
            Map<String, Object> r = stringKeys(rm);
            if (ContactIds.normalize(str(r.get("uuid"))) == null) {
                out.add(Finding.error(location + ":ref.uuid", "contact-ref", "ref.uuid is missing or not a UUID"));
            }
            String scope = str(r.get("scope"));
            if (scope != null && scopeOrNull(scope) == null) {
                out.add(Finding.error(
                        location + ":ref.scope",
                        "contact-ref",
                        "Unknown scope '" + scope + "' — user, project or tenant"));
            }
        } else {
            out.add(Finding.error(location + ":ref", "contact-shape", "'ref' must be a mapping"));
        }
        return out;
    }

    static void validateCard(Map<String, Object> m, String at, List<Finding> out) {
        for (String key : m.keySet()) {
            if (!CARD_FIELDS.contains(key)) {
                out.add(Finding.warning(
                        at + "." + key,
                        "contact-unknown-field",
                        "Unknown field '" + key + "' is ignored — put foreign data under 'extras'"));
            }
        }
        String kindRaw = str(m.get("kind"));
        if (kindRaw != null) {
            try {
                ContactKind.parseStrict(kindRaw);
            } catch (IllegalArgumentException e) {
                out.add(Finding.warning(at + ".kind", "contact-kind", e.getMessage() + " (read as individual)"));
            }
        }
        ContactCard card = cardFromMap(m);
        if (card.displayName().isEmpty()) {
            out.add(Finding.error(
                    at + ".fullName", "contact-name", "No fullName, name or org name — the card has no name"));
        }
        for (String field : VALUE_LIST_FIELDS) {
            List<Object> raw = asList(m.get(field));
            for (int i = 0; i < raw.size(); i++) {
                ContactValue v = valueFromObject(raw.get(i));
                String where = at + "." + field + "[" + i + "]";
                if (v == null) {
                    out.add(Finding.error(where, "contact-value", "Entry without a value is dropped"));
                } else if ("emails".equals(field) && !v.value().contains("@")) {
                    out.add(Finding.warning(
                            where, "contact-email", "'" + v.value() + "' does not look like an e-mail"));
                } else if ("phones".equals(field)
                        && ContactSearch.normalizePhone(v.value()).length() < 3) {
                    out.add(Finding.warning(where, "contact-phone", "'" + v.value() + "' has almost no digits"));
                }
            }
        }
        checkDate(m.get("birthday"), at + ".birthday", out);
        checkDate(m.get("anniversary"), at + ".anniversary", out);
        String photo = card.photo();
        if (photo != null && !photo.matches("^[0-9a-f-]{36}\\.(jpg|png)$")) {
            out.add(Finding.warning(
                    at + ".photo", "contact-photo", "photo should be '<uuid>.jpg' or '<uuid>.png' next to the card"));
        }
        if (card.kind() != ContactKind.GROUP && !card.members().isEmpty()) {
            out.add(Finding.warning(at + ".members", "contact-members", "members are only read on kind: group"));
        }
        for (int i = 0; i < card.members().size(); i++) {
            String member = card.members().get(i);
            if (ContactIds.normalize(member) == null && !ContactIds.isUri(member)) {
                out.add(Finding.warning(
                        at + ".members[" + i + "]",
                        "contact-member",
                        "'" + member + "' is neither a contact UUID nor a URI (mailto:…)"));
            }
        }
        if (card.org() != null
                && card.org().ref() != null
                && ContactIds.normalize(card.org().ref()) == null) {
            out.add(Finding.warning(at + ".org.ref", "contact-org-ref", "org.ref must be the UUID of an org card"));
        }
    }

    private static void checkDate(@Nullable Object raw, String where, List<Finding> out) {
        String s = str(raw);
        if (s != null && !DATE.matcher(s).matches()) {
            out.add(Finding.warning(where, "contact-date", "'" + s + "' is not yyyy-MM-dd or --MM-dd"));
        }
    }

    // ── Scalar helpers ────────────────────────────────────────────

    static @Nullable ContactScope scopeOrNull(@Nullable String raw) {
        try {
            return ContactScope.parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * A scalar as text. YAML resolves an unquoted {@code 1984-03-12} to a
     * {@link Date}; it comes back as the date the author typed (UTC), not as
     * {@code Date.toString()}. Numbers stay as written ({@code 10115} is a
     * postal code, not an integer).
     */
    public static @Nullable String str(@Nullable Object v) {
        if (v == null) return null;
        String s;
        if (v instanceof String str) {
            s = str;
        } else if (v instanceof Date d) {
            Instant instant = d.toInstant();
            LocalDate date = instant.atZone(ZoneOffset.UTC).toLocalDate();
            s = date.atStartOfDay(ZoneOffset.UTC).toInstant().equals(instant) ? date.toString() : instant.toString();
        } else if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) {
            s = new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
        } else if (v instanceof Number || v instanceof Boolean) {
            s = v.toString();
        } else {
            return null;
        }
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    private static String strOrEmpty(@Nullable Object v) {
        String s = str(v);
        return s == null ? "" : s;
    }

    /** A list of strings, tolerating a single string or a comma list where an array was meant. */
    public static List<String> strList(@Nullable Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                String s = str(o);
                if (s != null) out.add(s);
            }
        } else if (raw instanceof String s) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) out.add(part.trim());
            }
        } else {
            String s = str(raw);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static List<Object> asList(@Nullable Object raw) {
        if (raw instanceof List<?> list) return new ArrayList<>(list);
        if (raw == null) return List.of();
        return List.of(raw);
    }

    static @Nullable Integer intOrNull(@Nullable Object raw) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static Map<String, Object> stringKeys(Map<?, ?> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : in.entrySet()) {
            if (e.getKey() instanceof String k && e.getValue() != null) out.put(k, e.getValue());
        }
        return out;
    }

    private static void putOpt(Map<String, Object> m, String key, @Nullable String value) {
        if (value != null && !value.isEmpty()) m.put(key, value);
    }

    private static void putList(Map<String, Object> m, String key, List<String> values) {
        if (!values.isEmpty()) m.put(key, new ArrayList<>(values));
    }

    private static void putValues(Map<String, Object> m, String key, List<ContactValue> values) {
        if (values.isEmpty()) return;
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContactValue v : values) out.add(valueToMap(v));
        m.put(key, out);
    }
}
