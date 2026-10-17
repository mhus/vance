package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.contacts.model.ContactPin;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.shared.permission.WriteReason;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContactBoardServiceTest extends ContactTestWorld {

    private static final String BOARD = "people/meridian.yaml";

    private ContactBoardService boards;

    @BeforeEach
    void setUpBoards() {
        boards = new ContactBoardService(documentService, permissionService, service);
        boards.create(caller, BOARD, "Meridian");
    }

    @Test
    void pinStoresTheFoundScopeAndWritesAsTheUser() {
        writable.add(TENANT_PROJECT);
        ContactEntry anna = service.create(caller, card("Anna"), ContactScope.TENANT);

        ContactBoardService.PinResult result =
                boards.pin(caller, BOARD, anna.id(), null, "Einkauf", "Ruft gern an.", null);

        assertThat(result.warning()).isNull();
        assertThat(result.board().pins()).singleElement().satisfies(p -> {
            assertThat(p.pin()).isEqualTo(new ContactPin(anna.id(), ContactScope.TENANT, "Einkauf", "Ruft gern an."));
            assertThat(p.entry().card().displayName()).isEqualTo("Anna");
        });
        assertThat(actors).last().extracting(a -> a.reason()).isEqualTo(WriteReason.USER);
    }

    @Test
    void privateContactOnAProjectBoardWarns() {
        ContactEntry bob = service.create(caller, card("Bob"), ContactScope.USER);

        ContactBoardService.PinResult result = boards.pin(caller, BOARD, bob.id(), null, null, null, null);

        assertThat(result.warning()).contains("other readers");
    }

    @Test
    void pinningTwiceIsRefused() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        boards.pin(caller, BOARD, bob.id(), null, null, null, null);
        assertThatThrownBy(() -> boards.pin(caller, BOARD, bob.id(), null, null, null, null))
                .hasMessageContaining("already pinned");
    }

    @Test
    void updateReorderAndUnpin() {
        ContactEntry a = service.create(caller, card("A"), null);
        ContactEntry b = service.create(caller, card("B"), null);
        ContactEntry c = service.create(caller, card("C"), null);
        boards.pin(caller, BOARD, a.id(), null, null, null, null);
        boards.pin(caller, BOARD, b.id(), null, null, null, null);
        boards.pin(caller, BOARD, c.id(), null, null, null, 0);

        assertThat(ids(boards.get(caller, BOARD))).containsExactly(c.id(), a.id(), b.id());

        ContactBoardService.ResolvedBoard updated = boards.updatePin(caller, BOARD, a.id(), "Chef", "**wichtig**", 2);
        assertThat(ids(updated)).containsExactly(c.id(), b.id(), a.id());
        assertThat(updated.pins().get(2).pin().label()).isEqualTo("Chef");

        ContactBoardService.ResolvedBoard cleared = boards.updatePin(caller, BOARD, a.id(), "", null, null);
        assertThat(cleared.pins().get(2).pin().label()).isNull();
        assertThat(cleared.pins().get(2).pin().note()).isEqualTo("**wichtig**");

        // An order that misses a pin keeps it at the end instead of dropping it.
        assertThat(ids(boards.reorder(caller, BOARD, List.of(a.id(), c.id())))).containsExactly(a.id(), c.id(), b.id());

        assertThat(ids(boards.unpin(caller, BOARD, c.id()))).containsExactly(a.id(), b.id());
    }

    @Test
    void deletedContactKeepsHeadingAndNote() {
        ContactEntry bob = service.create(caller, card("Bob"), null);
        boards.pin(caller, BOARD, bob.id(), null, "Rechtsabteilung", "alt", null);
        service.delete(caller, bob.id(), null);

        ContactBoardService.ResolvedPin pin = boards.get(caller, BOARD).pins().get(0);

        assertThat(pin.entry()).isNull();
        assertThat(pin.pin().label()).isEqualTo("Rechtsabteilung");
    }

    @Test
    void otherReaderDoesNotSeeSomeoneElsesPrivateContact() {
        ContactEntry bob = service.create(caller, card("Bob"), ContactScope.USER);
        boards.pin(caller, BOARD, bob.id(), null, "Bob", null, null);

        ContactCaller colleague = ContactCaller.of(
                TENANT,
                de.mhus.vance.shared.permission.SecurityContext.user("carl", TENANT, List.of()),
                "carl",
                PROJECT);
        ContactBoardService.ResolvedPin pin =
                boards.get(colleague, BOARD).pins().get(0);

        assertThat(pin.entry()).isNull();
        assertThat(pin.pin().label()).isEqualTo("Bob");
    }

    @Test
    void refusesADocumentOfAnotherKind() {
        put(
                PROJECT,
                "notes/x.yaml",
                "$meta:\n  kind: contact\nref:\n  uuid: x\n".getBytes(StandardCharsets.UTF_8),
                "application/yaml");
        assertThatThrownBy(() -> boards.get(caller, "notes/x.yaml")).hasMessageContaining("not a contact-board");
    }

    private static List<String> ids(ContactBoardService.ResolvedBoard b) {
        return b.pins().stream().map(p -> p.pin().uuid()).toList();
    }
}
