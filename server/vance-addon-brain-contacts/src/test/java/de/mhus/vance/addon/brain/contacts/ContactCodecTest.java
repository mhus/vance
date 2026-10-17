package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.contacts.model.ContactAddress;
import de.mhus.vance.addon.brain.contacts.model.ContactBody;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactName;
import de.mhus.vance.addon.brain.contacts.model.ContactOrg;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContactCodecTest {

    private static final String ID = "3f0c8a1e-1111-4222-8333-444455556666";

    @Test
    void roundTripKeepsEveryField() {
        ContactCard card = ContactCard.builder()
                .kind(ContactKind.INDIVIDUAL)
                .fullName("Dr. Anna Berger")
                .name(new ContactName("Berger", "Anna", List.of(), List.of("Dr."), List.of()))
                .nicknames(List.of("Anni"))
                .emails(List.of(new ContactValue("anna@meridian.example", List.of("work"), 1)))
                .phones(List.of(ContactValue.of("+49 30 1234567", "work", "voice")))
                .addresses(List.of(new ContactAddress(
                        List.of("work"), null, null, null, "Hauptstraße 5", "Berlin", null, "10115", "DE", null)))
                .org(new ContactOrg("Meridian GmbH", List.of("Einkauf"), null))
                .title("Leiterin Einkauf")
                .birthday("1984-03-12")
                .categories(List.of("kunde"))
                .note("Mag kurze Mails.")
                .extras(Map.of("X-SOCIALPROFILE", "https://example.org/anna"))
                .build();

        String yaml = ContactCodec.serialize(card);
        ContactCard back = ContactCodec.parseCard(yaml);

        assertThat(yaml).contains("kind: contact").contains("fullName: Dr. Anna Berger");
        assertThat(back).isEqualTo(card);
    }

    @Test
    void lenientReadTakesBareStringsAndYamlScalars() {
        String yaml = """
                $meta:
                  kind: contact
                contact:
                  fullName: Bob
                  emails:
                    - bob@example.org
                  phones: "+49 170 1, +49 170 2"
                  org: ACME
                  birthday: 1984-03-12
                  addresses:
                    - street: Weg 1
                      postalCode: 10115
                """;

        ContactCard card = ContactCodec.parseCard(yaml);

        assertThat(card.emails()).extracting(ContactValue::value).containsExactly("bob@example.org");
        assertThat(card.phones()).hasSize(1); // a single string stays one value
        assertThat(card.org().name()).isEqualTo("ACME");
        assertThat(card.birthday()).isEqualTo("1984-03-12");
        assertThat(card.addresses().get(0).postalCode()).isEqualTo("10115");
    }

    @Test
    void unquotedDateSurvivesWriteAndRead() {
        ContactCard card =
                ContactCard.builder().fullName("X").birthday("1984-03-12").build();
        String yaml = ContactCodec.serialize(card);
        assertThat(ContactCodec.parseCard(yaml).birthday()).isEqualTo("1984-03-12");
    }

    @Test
    void parsesReference() {
        String yaml = ContactCodec.serializeRef(ID.toUpperCase(), ContactScope.TENANT);
        ContactBody body = ContactCodec.parse(yaml);
        assertThat(body).isEqualTo(new ContactBody.Ref(ID, ContactScope.TENANT));
    }

    @Test
    void refusesBodyWithoutCardOrRef() {
        assertThatThrownBy(() -> ContactCodec.parse("$meta:\n  kind: contact\nfoo: 1\n"))
                .isInstanceOf(KindCodecException.class);
    }

    @Test
    void groupMembersAreNormalised() {
        ContactCard card = ContactCodec.cardFromMap(Map.of(
                "kind", "group",
                "fullName", "Team",
                "members", List.of("urn:uuid:" + ID.toUpperCase(), "mailto:x@y.org", ID)));
        assertThat(card.members()).containsExactly(ID, "mailto:x@y.org");
    }

    @Test
    void validateReportsWhatTheLenientReaderDrops() {
        String yaml = """
                $meta:
                  kind: contact
                contact:
                  kind: robot
                  emails:
                    - value: not-an-email
                    - types: [work]
                  members: [nobody]
                  birthday: "12.03.1984"
                  colour: blue
                """;

        List<Finding> findings = ContactCodec.validate(yaml, "doc.yaml");

        assertThat(findings)
                .extracting(Finding::code)
                .contains(
                        "contact-kind",
                        "contact-name",
                        "contact-email",
                        "contact-value",
                        "contact-members",
                        "contact-member",
                        "contact-date",
                        "contact-unknown-field");
        assertThat(findings)
                .filteredOn(f -> f.code().equals("contact-value"))
                .extracting(Finding::location)
                .containsExactly("doc.yaml:contact.emails[1]");
    }

    @Test
    void validateAcceptsAWellFormedCard() {
        String yaml = ContactCodec.serialize(ContactCard.builder()
                .fullName("Anna")
                .emails(List.of(ContactValue.of("anna@example.org")))
                .build());
        assertThat(ContactCodec.validate(yaml, "x")).isEmpty();
    }

    @Test
    void validateFlagsBadReference() {
        String yaml = "$meta:\n  kind: contact\nref:\n  uuid: nope\n  scope: galaxy\n";
        assertThat(ContactCodec.validate(yaml, "x"))
                .extracting(Finding::code)
                .containsExactly("contact-ref", "contact-ref");
    }

    @Test
    void displayNameFallsBackToNameThenOrg() {
        assertThat(ContactCard.builder()
                        .name(ContactName.of("Anna", "Berger"))
                        .build()
                        .displayName())
                .isEqualTo("Anna Berger");
        assertThat(ContactCard.builder()
                        .org(new ContactOrg("ACME", List.of(), null))
                        .build()
                        .displayName())
                .isEqualTo("ACME");
    }
}
