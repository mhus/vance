package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.VCardVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * vCard import and export on top of {@link ContactService}. Import keeps
 * identity: a {@code UID} that is a UUID becomes the card id, any other
 * {@code UID} is remembered under {@code extras.X-VANCE-SOURCE-UID} and
 * matched on the next import of the same file — so importing an export of
 * the same address book twice updates instead of duplicating.
 */
@Service
@Slf4j
public class ContactVCardService {

    private static final String URN_UID = "urn:uid:";

    private final ContactService contactService;

    public ContactVCardService(ContactService contactService) {
        this.contactService = contactService;
    }

    /** One card the import did not take, and why. */
    public record Skipped(int index, String name, String reason) {}

    /**
     * What an import did.
     *
     * @param warnings things that were taken but not completely (a dropped
     *                 group member, a picture that would not decode)
     */
    public record ImportReport(
            ContactScope scope, int created, int updated, List<Skipped> skipped, List<String> warnings) {}

    /** Largest vCard text an import accepts (UTF-8 bytes). */
    public static final int MAX_IMPORT_BYTES = 5 * 1024 * 1024;

    /** Most cards one import takes. */
    public static final int MAX_IMPORT_CARDS = 5000;

    public ImportReport importVcf(ContactCaller caller, String vcf, @Nullable ContactScope scope) {
        if (utf8Length(vcf) > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException(
                    "The vCard file is larger than " + (MAX_IMPORT_BYTES / (1024 * 1024)) + " MB — split it");
        }
        ContactScope target =
                contactService.canonical(caller, scope == null ? contactService.defaultWriteScope(caller) : scope);
        // Resolve the project (and fail on a missing scope) before parsing anything.
        contactService.projectOf(caller, target);
        List<VCard> cards = Ezvcard.parse(vcf).all();
        if (cards.isEmpty()) throw new IllegalArgumentException("No vCard found in the input");
        if (cards.size() > MAX_IMPORT_CARDS) {
            throw new IllegalArgumentException("The file holds " + cards.size() + " vCards — at most "
                    + MAX_IMPORT_CARDS + " per import, split it");
        }

        record Item(int index, ContactVCardMapper.Imported imported) {}
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) items.add(new Item(i, ContactVCardMapper.fromVCard(cards.get(i))));
        // Groups last: their members must exist before the visibility check can pass.
        items.sort(Comparator.comparing(it -> it.imported().card().kind() == ContactKind.GROUP));

        int created = 0;
        int updated = 0;
        List<Skipped> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (ContactService.Bulk bulk = contactService.openBulk(caller, target)) {
            // Foreign UID (not a UUID) → the id it got in this run, so group
            // MEMBERs naming that UID point at the card instead of nowhere.
            // Seeded with the cards an earlier import made, so a re-import updates them.
            Map<String, String> foreignIds = new HashMap<>();
            for (ContactEntry e : bulk.entries()) {
                if (e.card().extras().get(ContactVCardMapper.X_SOURCE_UID) instanceof String source) {
                    foreignIds.putIfAbsent(source, e.id());
                }
            }
            for (Item item : items) {
                ContactCard card = item.imported().card();
                String label = card.displayName().isEmpty() ? "#" + (item.index() + 1) : card.displayName();
                try {
                    if (card.kind() == ContactKind.GROUP) {
                        List<String> members = remapMembers(card.members(), foreignIds);
                        List<String> kept = bulk.admissibleMembers(members);
                        if (kept.size() < members.size()) {
                            warnings.add(label + ": " + (members.size() - kept.size())
                                    + " member(s) dropped — unknown here or not visible from a " + target.wire()
                                    + " group");
                        }
                        card = card.toBuilder().members(kept).build();
                    }
                    String id = matchId(item.imported(), foreignIds);
                    boolean isNew = bulk.upsert(id, card);
                    if (sourceUid(item.imported()) instanceof String source) foreignIds.put(source, id);
                    if (isNew) created++;
                    else updated++;
                    if (item.imported().photo() != null) {
                        try {
                            bulk.setPhoto(id, item.imported().photo().getData());
                        } catch (RuntimeException e) {
                            // The card is in; a picture that does not decode or store costs only the picture.
                            warnings.add(label + ": picture not taken — " + e.getMessage());
                        }
                    } else if (hasPhotoUrl(cards.get(item.index()))) {
                        warnings.add(label + ": picture is only a link — not fetched");
                    }
                } catch (IllegalArgumentException e) {
                    skipped.add(new Skipped(item.index(), label, e.getMessage()));
                }
            }
        }
        log.info(
                "ContactVCardService: import into {} — created={} updated={} skipped={}",
                target.wire(),
                created,
                updated,
                skipped.size());
        return new ImportReport(target, created, updated, skipped, warnings);
    }

    /** vCard 4.0 text for the given entries, pictures embedded. */
    public String exportVcf(ContactCaller caller, List<ContactEntry> entries) {
        List<VCard> out = new ArrayList<>();
        for (ContactEntry e : entries) {
            ContactService.PhotoContent photo = null;
            if (e.photoPath() != null) {
                try {
                    photo = contactService.readPhoto(caller, e.id(), e.scope());
                } catch (ContactNotFoundException ex) {
                    photo = null;
                }
            }
            out.add(ContactVCardMapper.toVCard(e.id(), e.card(), photo));
        }
        return Ezvcard.write(out).version(VCardVersion.V4_0).prodId(false).go();
    }

    /** Id for an imported card: its UUID, the card already made of the same foreign UID, or a new one. */
    private static String matchId(ContactVCardMapper.Imported imported, Map<String, String> foreignIds) {
        if (imported.uid() != null) return imported.uid();
        String source = sourceUid(imported);
        String earlier = source == null ? null : foreignIds.get(source);
        return earlier != null ? earlier : ContactIds.newId();
    }

    private static @Nullable String sourceUid(ContactVCardMapper.Imported imported) {
        return imported.card().extras().get(ContactVCardMapper.X_SOURCE_UID) instanceof String s ? s : null;
    }

    /**
     * Group members that name a foreign UID imported in this run, rewritten to
     * the id that card got. A member is matched as written and without a
     * {@code urn:uid:} prefix; everything else stays as it came.
     */
    static List<String> remapMembers(List<String> members, Map<String, String> foreignIds) {
        List<String> out = new ArrayList<>(members.size());
        for (String member : members) {
            String id = foreignIds.get(member.trim());
            if (id == null && member.regionMatches(true, 0, URN_UID, 0, URN_UID.length())) {
                id = foreignIds.get(member.substring(URN_UID.length()).trim());
            }
            out.add(id != null ? id : member);
        }
        return out;
    }

    private static long utf8Length(String s) {
        // Cheap bound first: every char is at least one byte, at most three.
        if (s.length() > MAX_IMPORT_BYTES) return s.length();
        if ((long) s.length() * 3 <= MAX_IMPORT_BYTES) return s.length();
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean hasPhotoUrl(VCard v) {
        return v.getPhotos().stream().anyMatch(p -> p.getUrl() != null);
    }
}
