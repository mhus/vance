package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.VCardVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    public ImportReport importVcf(ContactCaller caller, String vcf, @Nullable ContactScope scope) {
        ContactScope target =
                contactService.canonical(caller, scope == null ? contactService.defaultWriteScope(caller) : scope);
        // Resolve the project (and fail on a missing scope) before parsing anything.
        contactService.projectOf(caller, target);
        List<VCard> cards = Ezvcard.parse(vcf).all();
        if (cards.isEmpty()) throw new IllegalArgumentException("No vCard found in the input");

        record Item(int index, ContactVCardMapper.Imported imported) {}
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) items.add(new Item(i, ContactVCardMapper.fromVCard(cards.get(i))));
        // Groups last: their members must exist before the visibility check can pass.
        items.sort(Comparator.comparing(it -> it.imported().card().kind() == ContactKind.GROUP));

        List<ContactEntry> existing = contactService.entries(caller, target);
        int created = 0;
        int updated = 0;
        List<Skipped> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Item item : items) {
            ContactCard card = item.imported().card();
            String label = card.displayName().isEmpty() ? "#" + (item.index() + 1) : card.displayName();
            try {
                if (card.kind() == ContactKind.GROUP) {
                    List<String> kept = contactService.admissibleMembers(caller, card.members(), target);
                    if (kept.size() < card.members().size()) {
                        warnings.add(label + ": " + (card.members().size() - kept.size())
                                + " member(s) dropped — unknown here or not visible from a " + target.wire()
                                + " group");
                    }
                    card = card.toBuilder().members(kept).build();
                }
                String id = matchId(item.imported(), existing);
                boolean isNew = contactService.upsert(caller, id, card, target);
                if (isNew) created++;
                else updated++;
                if (item.imported().photo() != null) {
                    try {
                        contactService.setPhoto(
                                caller, id, target, item.imported().photo().getData());
                    } catch (IllegalArgumentException e) {
                        warnings.add(label + ": picture not taken — " + e.getMessage());
                    }
                } else if (hasPhotoUrl(cards.get(item.index()))) {
                    warnings.add(label + ": picture is only a link — not fetched");
                }
            } catch (IllegalArgumentException e) {
                skipped.add(new Skipped(item.index(), label, e.getMessage()));
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

    /** Id for an imported card: its UUID, the earlier import of the same foreign UID, or a new one. */
    private static String matchId(ContactVCardMapper.Imported imported, List<ContactEntry> existing) {
        if (imported.uid() != null) return imported.uid();
        Object source = imported.card().extras().get(ContactVCardMapper.X_SOURCE_UID);
        if (source != null) {
            for (ContactEntry e : existing) {
                if (source.equals(e.card().extras().get(ContactVCardMapper.X_SOURCE_UID))) return e.id();
            }
        }
        return ContactIds.newId();
    }

    private static boolean hasPhotoUrl(VCard v) {
        return v.getPhotos().stream().anyMatch(p -> p.getUrl() != null);
    }
}
