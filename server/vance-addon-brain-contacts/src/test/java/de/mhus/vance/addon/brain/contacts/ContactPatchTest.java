package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContactPatchTest {

    private final ContactCard stored = ContactCard.builder()
            .fullName("Anna")
            .title("CEO")
            .photo("x.jpg")
            .emails(List.of(ContactValue.of("a@x.org", "work")))
            .phones(List.of(ContactValue.of("+49 170 1")))
            .categories(List.of("kunde"))
            .build();

    @Test
    void absentFieldsStay() {
        ContactCard out = ContactPatch.apply(stored, Map.of("title", "CTO"));
        assertThat(out.title()).isEqualTo("CTO");
        assertThat(out.emails()).isEqualTo(stored.emails());
        assertThat(out.photo()).isEqualTo("x.jpg");
    }

    @Test
    void emptyValuesClear() {
        Map<String, Object> params = new HashMap<>();
        params.put("title", "");
        params.put("categories", List.of());
        params.put("note", null);
        ContactCard out = ContactPatch.apply(stored, params);
        assertThat(out.title()).isNull();
        assertThat(out.categories()).isEmpty();
    }

    @Test
    void addAndRemoveSingleValues() {
        ContactCard out = ContactPatch.apply(
                stored,
                Map.of(
                        "addPhones", List.of("+49 30 2", Map.of("value", "0170 1")),
                        "removeEmails", List.of("A@X.org"),
                        "addEmails", List.of(Map.of("value", "b@x.org", "types", List.of("home"))),
                        "addCategories", List.of("Kunde", "vip"),
                        "removeCategories", List.of("KUNDE")));
        assertThat(out.phones()).extracting(ContactValue::value).containsExactly("+49 170 1", "+49 30 2", "0170 1");
        assertThat(out.emails()).extracting(ContactValue::value).containsExactly("b@x.org");
        assertThat(out.categories()).containsExactly("vip");
    }

    @Test
    void removePhoneMatchesNotation() {
        ContactCard out = ContactPatch.apply(stored, Map.of("removePhones", List.of("+49-170-1")));
        assertThat(out.phones()).isEmpty();
    }

    @Test
    void detectsEmptyPatch() {
        assertThat(ContactPatch.isEmpty(Map.of("id", "x", "scope", "user"))).isTrue();
        assertThat(ContactPatch.isEmpty(Map.of("addPhones", List.of("1")))).isFalse();
    }
}
