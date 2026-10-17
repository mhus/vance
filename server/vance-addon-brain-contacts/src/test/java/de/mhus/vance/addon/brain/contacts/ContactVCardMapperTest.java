package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactName;
import de.mhus.vance.addon.brain.contacts.model.ContactOrg;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.VCardVersion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContactVCardMapperTest {

    private static final String ID = "3f0c8a1e-1111-4222-8333-444455556666";

    /** The shape Google Contacts and older Apple exports produce. */
    private static final String V3 = """
            BEGIN:VCARD
            VERSION:3.0
            UID:google-12345
            FN:Anna Berger
            N:Berger;Anna;;Dr.;
            EMAIL;TYPE=INTERNET;TYPE=WORK;TYPE=PREF:anna@meridian.example
            EMAIL;TYPE=INTERNET;TYPE=HOME:anna@private.example
            TEL;TYPE=CELL:+49 170 7654321
            ADR;TYPE=WORK:;;Hauptstraße 5;Berlin;;10115;Germany
            ORG:Meridian GmbH;Einkauf
            TITLE:Leiterin Einkauf
            BDAY:1984-03-12
            CATEGORIES:kunde,berlin
            NOTE:Mag kurze Mails.
            X-SOCIALPROFILE:https://example.org/anna
            END:VCARD
            """;

    @Test
    void readsVersion3WithOldHabits() {
        VCard v = Ezvcard.parse(V3).first();
        ContactVCardMapper.Imported imported = ContactVCardMapper.fromVCard(v);
        ContactCard c = imported.card();

        assertThat(imported.uid()).isNull();
        assertThat(c.extras()).containsEntry(ContactVCardMapper.X_SOURCE_UID, "google-12345");
        assertThat(c.fullName()).isEqualTo("Anna Berger");
        assertThat(c.name()).isEqualTo(new ContactName("Berger", "Anna", List.of(), List.of("Dr."), List.of()));
        assertThat(c.emails())
                .containsExactly(
                        new ContactValue("anna@meridian.example", List.of("work"), 1),
                        new ContactValue("anna@private.example", List.of("home"), null));
        assertThat(c.phones()).containsExactly(new ContactValue("+49 170 7654321", List.of("cell"), null));
        assertThat(c.addresses().get(0).street()).isEqualTo("Hauptstraße 5");
        assertThat(c.addresses().get(0).country()).isEqualTo("Germany");
        assertThat(c.org()).isEqualTo(new ContactOrg("Meridian GmbH", List.of("Einkauf"), null));
        assertThat(c.birthday()).isEqualTo("1984-03-12");
        assertThat(c.categories()).containsExactly("kunde", "berlin");
        assertThat(c.extras()).containsEntry("X-SOCIALPROFILE", "https://example.org/anna");
    }

    @Test
    void writesVersion4AndReadsItBack() {
        ContactCard card = ContactCard.builder()
                .fullName("Anna Berger")
                .name(ContactName.of("Anna", "Berger"))
                .emails(List.of(new ContactValue("anna@meridian.example", List.of("work"), 1)))
                .phones(List.of(ContactValue.of("+49 170 7654321", "cell")))
                .org(new ContactOrg("Meridian GmbH", List.of(), null))
                .birthday("--03-12")
                .categories(List.of("kunde"))
                .vanceUser("anna")
                .extras(Map.of("X-SOCIALPROFILE", "https://example.org/anna"))
                .build();

        String vcf = Ezvcard.write(ContactVCardMapper.toVCard(ID, card, null))
                .version(VCardVersion.V4_0)
                .go();
        ContactVCardMapper.Imported back =
                ContactVCardMapper.fromVCard(Ezvcard.parse(vcf).first());

        assertThat(vcf).contains("VERSION:4.0").contains("UID:urn:uuid:" + ID);
        assertThat(back.uid()).isEqualTo(ID);
        assertThat(back.card()).isEqualTo(card);
    }

    @Test
    void groupMembersTravelAsUrnUuid() {
        String member = "9a1d0000-0000-4000-8000-000000000044";
        ContactCard group = ContactCard.builder()
                .kind(ContactKind.GROUP)
                .fullName("Team")
                .members(List.of(member, "mailto:x@y.org"))
                .build();
        String vcf = Ezvcard.write(ContactVCardMapper.toVCard(ID, group, null))
                .version(VCardVersion.V4_0)
                .go();

        assertThat(vcf).contains("MEMBER:urn:uuid:" + member);
        ContactCard back =
                ContactVCardMapper.fromVCard(Ezvcard.parse(vcf).first()).card();
        assertThat(back.kind()).isEqualTo(ContactKind.GROUP);
        assertThat(back.members()).containsExactly(member, "mailto:x@y.org");
    }
}
