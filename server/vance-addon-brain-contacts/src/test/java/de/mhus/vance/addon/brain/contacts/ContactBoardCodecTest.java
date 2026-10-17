package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.contacts.model.ContactBoard;
import de.mhus.vance.addon.brain.contacts.model.ContactPin;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContactBoardCodecTest {

    private static final String A = "3f0c8a1e-1111-4222-8333-444455556666";
    private static final String B = "9a1d0000-0000-4000-8000-000000000044";

    @Test
    void roundTrip() {
        ContactBoard board = new ContactBoard(
                "Vertrag Meridian",
                null,
                List.of(
                        new ContactPin(A, ContactScope.TENANT, "Einkauf", "Entscheidet über **Rahmenverträge**."),
                        new ContactPin(B, null, null, null)));

        String yaml = ContactBoardCodec.serialize(board);

        assertThat(yaml).contains("kind: contact-board");
        assertThat(ContactBoardCodec.parse(yaml)).isEqualTo(board);
    }

    @Test
    void lenientReadDropsUnreadableAndDuplicatePins() {
        String yaml = """
                $meta:
                  kind: contact-board
                pins:
                  - uuid: %s
                    label: first
                  - uuid: nope
                  - uuid: %s
                    label: again
                  - plain string
                """.formatted(A, A.toUpperCase());

        ContactBoard board = ContactBoardCodec.parse(yaml);

        assertThat(board.pins()).extracting(ContactPin::label).containsExactly("first");
        assertThat(ContactBoardCodec.validate(yaml, "b.yaml"))
                .extracting(Finding::code)
                .containsExactly("contact-board-uuid", "contact-board-duplicate", "contact-board-shape");
    }

    @Test
    void validateAcceptsAWellFormedBoard() {
        String yaml = ContactBoardCodec.serialize(
                new ContactBoard("T", null, List.of(new ContactPin(A, ContactScope.USER, "x", "y"))));
        assertThat(ContactBoardCodec.validate(yaml, "b.yaml")).isEmpty();
    }
}
