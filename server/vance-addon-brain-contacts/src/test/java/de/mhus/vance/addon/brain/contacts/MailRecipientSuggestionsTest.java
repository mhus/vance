package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import de.mhus.vance.api.form.FormSuggestionDto;
import de.mhus.vance.brain.suggestion.SuggestionRequest;
import de.mhus.vance.shared.permission.SecurityContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class MailRecipientSuggestionsTest extends ContactTestWorld {

    private List<FormSuggestionDto> suggest(String login, String query) {
        return new MailRecipientSuggestions(service)
                .suggest(new SuggestionRequest(
                        TENANT, SecurityContext.user(login, TENANT, List.of()), login, PROJECT, query, 8));
    }

    @Test
    void personSuggestsEachAddressPreferredFirst() {
        service.create(
                caller,
                ContactCard.builder()
                        .fullName("Anna Berger")
                        .emails(List.of(
                                ContactValue.of("anna@home.example"),
                                new ContactValue("anna@work.example", List.of(), 1)))
                        .build(),
                null);

        assertThat(suggest(USER, "anna"))
                .extracting(FormSuggestionDto::values)
                .containsExactly(List.of("anna@work.example"), List.of("anna@home.example"));
    }

    @Test
    void contactWithoutAddressIsNoSuggestion() {
        service.create(caller, ContactCard.builder().fullName("Bob").build(), null);
        assertThat(suggest(USER, "bob")).isEmpty();
    }

    @Test
    void groupInsertsAllMemberAddressesAndNamesTheGaps() {
        ContactEntry anna = service.create(
                caller,
                ContactCard.builder()
                        .fullName("Anna")
                        .emails(List.of(ContactValue.of("anna@x.org")))
                        .build(),
                null);
        ContactEntry bob =
                service.create(caller, ContactCard.builder().fullName("Bob").build(), null);
        service.create(
                caller,
                ContactCard.builder()
                        .kind(ContactKind.GROUP)
                        .fullName("Team Meridian")
                        .members(List.of(anna.id(), bob.id(), "mailto:extern@y.org"))
                        .build(),
                null);

        FormSuggestionDto group = suggest(USER, "meridian").get(0);

        assertThat(group.kind()).isEqualTo("group");
        assertThat(group.values()).containsExactly("anna@x.org", "extern@y.org");
        assertThat(group.note()).isEqualTo("1 member without an address");
    }

    @Test
    void nobodyGetsSomeoneElsesPrivateAddresses() {
        service.create(
                caller,
                ContactCard.builder()
                        .fullName("Secret Sam")
                        .emails(List.of(ContactValue.of("sam@x.org")))
                        .build(),
                ContactScope.USER);

        assertThat(suggest(USER, "sam")).hasSize(1);
        assertThat(suggest("carl", "sam")).isEmpty();
    }
}
