package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactAddress;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactName;
import de.mhus.vance.addon.brain.contacts.model.ContactOrg;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import ezvcard.VCard;
import ezvcard.VCardVersion;
import ezvcard.parameter.ImageType;
import ezvcard.property.Address;
import ezvcard.property.Anniversary;
import ezvcard.property.Birthday;
import ezvcard.property.Categories;
import ezvcard.property.DateOrTimeProperty;
import ezvcard.property.Email;
import ezvcard.property.Impp;
import ezvcard.property.Kind;
import ezvcard.property.Language;
import ezvcard.property.Member;
import ezvcard.property.Nickname;
import ezvcard.property.Note;
import ezvcard.property.Organization;
import ezvcard.property.Photo;
import ezvcard.property.RawProperty;
import ezvcard.property.Related;
import ezvcard.property.Role;
import ezvcard.property.StructuredName;
import ezvcard.property.Telephone;
import ezvcard.property.Timezone;
import ezvcard.property.Title;
import ezvcard.property.Uid;
import ezvcard.property.Url;
import ezvcard.property.VCardProperty;
import ezvcard.util.PartialDate;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * vCard ⇄ {@link ContactCard}. Reads 2.1, 3.0 and 4.0 (what Google, Apple and
 * Outlook export), writes 4.0.
 *
 * <p>Two older-version habits are folded on the way in: a {@code PREF}
 * <em>type</em> (3.0) becomes {@code pref: 1}, and the e-mail type
 * {@code INTERNET} — which only says "this is an e-mail" — is dropped.
 * Properties the model has no field for land in {@code extras} under their
 * property name (string values only), so they travel back out on export.
 */
public final class ContactVCardMapper {

    /** Extended property carrying {@link ContactCard#vanceUser()}. */
    static final String X_VANCE_USER = "X-VANCE-USER";

    /** Extras key for a foreign {@code UID} that is not a UUID — the re-import match key. */
    public static final String X_SOURCE_UID = "X-VANCE-SOURCE-UID";

    private ContactVCardMapper() {}

    /** A parsed card with what came along that the card itself does not hold. */
    public record Imported(
            ContactCard card,
            @Nullable String uid,
            @Nullable Photo photo) {}

    // ── vCard → card ──────────────────────────────────────────────

    public static Imported fromVCard(VCard v) {
        ContactKind kind = ContactKind.INDIVIDUAL;
        Kind k = v.getKind();
        if (k != null) {
            if (k.isGroup()) kind = ContactKind.GROUP;
            else if (k.isOrg()) kind = ContactKind.ORG;
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        for (RawProperty raw : v.getExtendedProperties()) {
            String name = raw.getPropertyName().toUpperCase(Locale.ROOT);
            if (X_VANCE_USER.equals(name) || raw.getValue() == null) continue;
            appendExtra(extras, name, raw.getValue());
        }
        String uidRaw = v.getUid() == null ? null : v.getUid().getValue();
        String uid = ContactIds.normalize(uidRaw);
        if (uid == null && uidRaw != null && !uidRaw.isBlank()) extras.put(X_SOURCE_UID, uidRaw.trim());

        List<String> nicknames = new ArrayList<>();
        for (Nickname n : v.getNicknames()) nicknames.addAll(n.getValues());

        ContactOrg org = null;
        Organization o = v.getOrganization();
        if (o != null && !o.getValues().isEmpty()) {
            List<String> parts = o.getValues();
            org = new ContactOrg(parts.get(0), parts.subList(1, parts.size()), null);
        }

        List<String> categories = new ArrayList<>();
        for (Categories c : v.getCategoriesList()) categories.addAll(c.getValues());

        List<ContactValue> related = new ArrayList<>();
        for (Related r : v.getRelations()) {
            String value = r.getUri() != null ? r.getUri() : r.getText();
            if (value == null) continue;
            String id = ContactIds.normalize(value);
            related.add(new ContactValue(id != null ? id : value, types(r), r.getPref()));
        }

        List<String> members = new ArrayList<>();
        for (Member m : v.getMembers()) {
            if (m.getUri() == null) continue;
            String id = ContactIds.normalize(m.getUri());
            members.add(id != null ? id : m.getUri());
        }

        StringBuilder note = new StringBuilder();
        for (Note n : v.getNotes()) {
            if (n.getValue() == null || n.getValue().isBlank()) continue;
            if (!note.isEmpty()) note.append("\n\n");
            note.append(n.getValue().strip());
        }

        RawProperty vanceUser = v.getExtendedProperty(X_VANCE_USER);
        String fullName =
                v.getFormattedName() == null ? null : v.getFormattedName().getValue();

        ContactCard card = ContactCard.builder()
                .kind(kind)
                .fullName(fullName == null ? "" : fullName)
                .name(name(v.getStructuredName()))
                .nicknames(nicknames)
                .emails(emails(v.getEmails()))
                .phones(phones(v.getTelephoneNumbers()))
                .addresses(addresses(v.getAddresses()))
                .org(org)
                .title(v.getTitles().isEmpty() ? null : v.getTitles().get(0).getValue())
                .role(v.getRoles().isEmpty() ? null : v.getRoles().get(0).getValue())
                .birthday(date(v.getBirthday()))
                .anniversary(date(v.getAnniversary()))
                .urls(urls(v.getUrls()))
                .impps(impps(v.getImpps()))
                .languages(v.getLanguages().stream()
                        .map(Language::getValue)
                        .filter(s -> s != null)
                        .toList())
                .timezone(timezone(v.getTimezone()))
                .categories(categories)
                .note(note.isEmpty() ? null : note.toString())
                .related(related)
                .members(kind == ContactKind.GROUP ? members : List.of())
                .vanceUser(vanceUser == null ? null : vanceUser.getValue())
                .extras(extras)
                .build();
        Photo photo = v.getPhotos().stream()
                .filter(p -> p.getData() != null && p.getData().length > 0)
                .findFirst()
                .orElse(null);
        return new Imported(card, uid, photo);
    }

    private static @Nullable ContactName name(@Nullable StructuredName n) {
        if (n == null) return null;
        ContactName name =
                new ContactName(n.getFamily(), n.getGiven(), n.getAdditionalNames(), n.getPrefixes(), n.getSuffixes());
        return name.isEmpty() ? null : name;
    }

    private static List<ContactValue> emails(List<Email> in) {
        List<ContactValue> out = new ArrayList<>();
        for (Email e : in) {
            if (e.getValue() == null) continue;
            out.add(value(e.getValue(), e, "internet"));
        }
        return out;
    }

    private static List<ContactValue> phones(List<Telephone> in) {
        List<ContactValue> out = new ArrayList<>();
        for (Telephone t : in) {
            String number = t.getText() != null
                    ? t.getText()
                    : t.getUri() == null ? null : t.getUri().getNumber();
            if (number == null) continue;
            out.add(value(number, t, null));
        }
        return out;
    }

    private static List<ContactAddress> addresses(List<Address> in) {
        List<ContactAddress> out = new ArrayList<>();
        for (Address a : in) {
            ParsedTypes pt = parseTypes(a, null);
            ContactAddress address = new ContactAddress(
                    pt.types(),
                    pt.pref(),
                    a.getPoBox(),
                    a.getExtendedAddressFull(),
                    a.getStreetAddressFull(),
                    a.getLocality(),
                    a.getRegion(),
                    a.getPostalCode(),
                    a.getCountry(),
                    a.getLabel());
            if (!address.isEmpty()) out.add(address);
        }
        return out;
    }

    private static List<ContactValue> urls(List<Url> in) {
        List<ContactValue> out = new ArrayList<>();
        for (Url u : in) {
            if (u.getValue() == null) continue;
            ContactValue v = value(u.getValue(), u, null);
            if (u.getType() != null && !v.types().contains(u.getType().toLowerCase(Locale.ROOT))) {
                List<String> t = new ArrayList<>(v.types());
                t.add(u.getType().toLowerCase(Locale.ROOT));
                v = new ContactValue(v.value(), t, v.pref());
            }
            out.add(v);
        }
        return out;
    }

    private static List<ContactValue> impps(List<Impp> in) {
        List<ContactValue> out = new ArrayList<>();
        for (Impp i : in) {
            if (i.getUri() == null) continue;
            out.add(value(i.getUri().toString(), i, null));
        }
        return out;
    }

    private static @Nullable String date(@Nullable DateOrTimeProperty p) {
        if (p == null) return null;
        Temporal t = p.getDate();
        if (t != null) {
            try {
                return LocalDate.from(t).toString();
            } catch (RuntimeException e) {
                return t.toString();
            }
        }
        PartialDate pd = p.getPartialDate();
        if (pd != null && pd.getMonth() != null && pd.getDate() != null) {
            String md = String.format(Locale.ROOT, "%02d-%02d", pd.getMonth(), pd.getDate());
            return pd.getYear() == null ? "--" + md : String.format(Locale.ROOT, "%04d-%s", pd.getYear(), md);
        }
        return p.getText();
    }

    private static @Nullable String timezone(@Nullable Timezone tz) {
        if (tz == null) return null;
        if (tz.getText() != null) return tz.getText();
        return tz.getOffset() == null ? null : tz.getOffset().getId();
    }

    private record ParsedTypes(List<String> types, @Nullable Integer pref) {}

    private static ContactValue value(String value, VCardProperty p, @Nullable String noiseType) {
        ParsedTypes pt = parseTypes(p, noiseType);
        return new ContactValue(value, pt.types(), pt.pref());
    }

    private static List<String> types(VCardProperty p) {
        return parseTypes(p, null).types();
    }

    /** TYPE parameters lower-cased; a 3.0 {@code TYPE=PREF} becomes {@code pref: 1}. */
    private static ParsedTypes parseTypes(VCardProperty p, @Nullable String noiseType) {
        Integer pref = p.getParameters().getPref();
        List<String> types = new ArrayList<>();
        for (String raw : p.getParameters().getTypes()) {
            for (String t : raw.split(",")) {
                String type = t.trim().toLowerCase(Locale.ROOT);
                if (type.isEmpty() || type.equals(noiseType)) continue;
                if (type.equals("pref")) {
                    if (pref == null) pref = 1;
                    continue;
                }
                if (!types.contains(type)) types.add(type);
            }
        }
        return new ParsedTypes(types, pref);
    }

    @SuppressWarnings("unchecked")
    private static void appendExtra(Map<String, Object> extras, String name, String value) {
        Object existing = extras.get(name);
        if (existing == null) {
            extras.put(name, value);
        } else if (existing instanceof List<?> list) {
            ((List<Object>) list).add(value);
        } else {
            List<Object> list = new ArrayList<>();
            list.add(existing);
            list.add(value);
            extras.put(name, list);
        }
    }

    // ── card → vCard ──────────────────────────────────────────────

    /**
     * @param photo the picture to embed, or {@code null}
     */
    public static VCard toVCard(String id, ContactCard c, ContactService.@Nullable PhotoContent photo) {
        VCard v = new VCard(VCardVersion.V4_0);
        v.setUid(new Uid("urn:uuid:" + id));
        v.setKind(
                switch (c.kind()) {
                    case GROUP -> Kind.group();
                    case ORG -> Kind.org();
                    case INDIVIDUAL -> Kind.individual();
                });
        v.setFormattedName(c.displayName());
        if (c.name() != null) {
            StructuredName n = new StructuredName();
            n.setFamily(c.name().family());
            n.setGiven(c.name().given());
            n.getAdditionalNames().addAll(c.name().additional());
            n.getPrefixes().addAll(c.name().prefixes());
            n.getSuffixes().addAll(c.name().suffixes());
            v.setStructuredName(n);
        }
        if (!c.nicknames().isEmpty()) v.setNickname(c.nicknames().toArray(String[]::new));
        if (photo != null) {
            ImageType type = photo.mimeType().contains("png") ? ImageType.PNG : ImageType.JPEG;
            v.addPhoto(new Photo(photo.bytes(), type));
        }
        for (ContactValue e : c.emails()) v.addEmail(typed(new Email(e.value()), e));
        for (ContactValue t : c.phones()) v.addTelephoneNumber(typed(new Telephone(t.value()), t));
        for (ContactAddress a : c.addresses()) {
            Address adr = new Address();
            adr.setPoBox(a.poBox());
            adr.setExtendedAddress(a.extended());
            adr.setStreetAddress(a.street());
            adr.setLocality(a.locality());
            adr.setRegion(a.region());
            adr.setPostalCode(a.postalCode());
            adr.setCountry(a.country());
            adr.setLabel(a.label());
            for (String t : a.types()) adr.getParameters().addType(t);
            if (a.pref() != null) adr.getParameters().setPref(a.pref());
            v.addAddress(adr);
        }
        if (c.org() != null && c.org().name() != null) {
            List<String> parts = new ArrayList<>();
            parts.add(c.org().name());
            parts.addAll(c.org().units());
            v.setOrganization(parts.toArray(String[]::new));
        }
        if (c.title() != null) v.addTitle(new Title(c.title()));
        if (c.role() != null) v.addRole(new Role(c.role()));
        if (c.birthday() != null)
            v.setBirthday(dateProperty(c.birthday(), Birthday::new, Birthday::new, Birthday::new));
        if (c.anniversary() != null) {
            v.setAnniversary(dateProperty(c.anniversary(), Anniversary::new, Anniversary::new, Anniversary::new));
        }
        for (ContactValue u : c.urls()) v.addUrl(typed(new Url(u.value()), u));
        for (ContactValue i : c.impps()) v.addImpp(typed(new Impp(i.value()), i));
        for (String lang : c.languages()) v.addLanguage(lang);
        if (c.timezone() != null) v.setTimezone(new Timezone(c.timezone()));
        if (!c.categories().isEmpty()) v.setCategories(c.categories().toArray(String[]::new));
        if (c.note() != null) v.addNote(c.note());
        for (ContactValue r : c.related()) {
            String id2 = ContactIds.normalize(r.value());
            Related rel = new Related();
            if (id2 != null) rel.setUri("urn:uuid:" + id2);
            else if (ContactIds.isUri(r.value())) rel.setUri(r.value());
            else rel.setText(r.value());
            v.addRelated(typed(rel, r));
        }
        if (c.kind() == ContactKind.GROUP) {
            for (String m : c.members()) {
                String mid = ContactIds.normalize(m);
                v.addMember(new Member(mid != null ? "urn:uuid:" + mid : m));
            }
        }
        if (c.vanceUser() != null) v.addExtendedProperty(X_VANCE_USER, c.vanceUser());
        for (Map.Entry<String, Object> e : c.extras().entrySet()) {
            String name = e.getKey().toUpperCase(Locale.ROOT);
            if (!name.startsWith("X-")) name = "X-" + name;
            if (e.getValue() instanceof List<?> list) {
                for (Object o : list) {
                    String s = ContactCodec.str(o);
                    if (s != null) v.addExtendedProperty(name, s);
                }
            } else {
                String s = ContactCodec.str(e.getValue());
                if (s != null) v.addExtendedProperty(name, s);
            }
        }
        return v;
    }

    private static <T extends VCardProperty> T typed(T prop, ContactValue value) {
        for (String t : value.types()) prop.getParameters().addType(t);
        if (value.pref() != null) prop.getParameters().setPref(value.pref());
        return prop;
    }

    private static <T extends DateOrTimeProperty> T dateProperty(
            String raw,
            java.util.function.Function<Temporal, T> ofDate,
            java.util.function.Function<PartialDate, T> ofPartial,
            java.util.function.Function<String, T> ofText) {
        try {
            return ofDate.apply(LocalDate.parse(raw));
        } catch (DateTimeParseException e) {
            // fall through
        }
        if (raw.startsWith("--")) {
            try {
                return ofPartial.apply(PartialDate.parse(raw));
            } catch (IllegalArgumentException e) {
                // fall through
            }
        }
        return ofText.apply(raw);
    }
}
