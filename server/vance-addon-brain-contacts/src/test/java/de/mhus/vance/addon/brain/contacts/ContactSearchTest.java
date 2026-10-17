package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactOrg;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContactSearchTest {

    private static ContactEntry entry(String id, ContactCard card) {
        return new ContactEntry(id, ContactScope.USER, "_user_x", "doc-" + id, card, null, null);
    }

    private final ContactEntry anna = entry(
            "a",
            ContactCard.builder()
                    .fullName("Anna Müller")
                    .emails(List.of(ContactValue.of("anna@meridian.example")))
                    .phones(List.of(ContactValue.of("+49 170 7654321")))
                    .org(new ContactOrg("Meridian GmbH", List.of(), null))
                    .categories(List.of("kunde"))
                    .note("met in Berlin")
                    .build());
    private final ContactEntry annette =
            entry("b", ContactCard.builder().fullName("Annette Berlin").build());
    private final ContactEntry team = entry(
            "c",
            ContactCard.builder()
                    .kind(ContactKind.GROUP)
                    .fullName("Team Meridian")
                    .build());

    @Test
    void phoneMatchesAcrossNotations() {
        assertThat(ContactSearch.phoneMatches("+49 170 7654321", "0170 7654321"))
                .isTrue();
        assertThat(ContactSearch.phoneMatches("+49 170 7654321", "0049-170-765 4321"))
                .isTrue();
        assertThat(ContactSearch.phoneMatches("+49 170 7654321", "0170 1111111"))
                .isFalse();
        assertThat(ContactSearch.normalizePhone("+49 (30) 123-45")).isEqualTo("+493012345");
    }

    @Test
    void foldsDiacritics() {
        assertThat(ContactSearch.search(List.of(anna, annette), ContactSearch.Criteria.query("muller")))
                .containsExactly(anna);
    }

    @Test
    void everyWordMustMatchAndNameBeatsNote() {
        // "berlin" is Annette's name but only Anna's note.
        assertThat(ContactSearch.search(List.of(anna, annette), ContactSearch.Criteria.query("berlin")))
                .containsExactly(annette, anna);
        assertThat(ContactSearch.search(List.of(anna, annette), ContactSearch.Criteria.query("anna berlin")))
                .containsExactly(anna);
    }

    @Test
    void findsByPhoneInFreeText() {
        assertThat(ContactSearch.search(List.of(anna, annette), ContactSearch.Criteria.query("0170 7654321")))
                .containsExactly(anna);
    }

    @Test
    void filtersNarrow() {
        List<ContactEntry> all = List.of(anna, annette, team);
        assertThat(ContactSearch.search(
                        all, new ContactSearch.Criteria(null, null, null, null, null, ContactKind.GROUP)))
                .containsExactly(team);
        assertThat(ContactSearch.search(all, new ContactSearch.Criteria(null, null, null, "meridian", null, null)))
                .containsExactly(anna);
        assertThat(ContactSearch.search(all, new ContactSearch.Criteria(null, null, null, null, "KUNDE", null)))
                .containsExactly(anna);
        assertThat(ContactSearch.search(
                        all, new ContactSearch.Criteria(null, "meridian.example", null, null, null, null)))
                .containsExactly(anna);
    }

    @Test
    void emptyQueryListsAlphabetically() {
        assertThat(ContactSearch.search(List.of(team, anna, annette), ContactSearch.Criteria.all()))
                .containsExactly(anna, annette, team);
    }
}
