package de.mhus.vance.addon.brain.contacts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.contacts.ContactBoardService;
import de.mhus.vance.addon.brain.contacts.model.ContactBoard;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContactBoardControllerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "website";
    private static final String PATH = "people/team.yaml";
    private static final String ID = "0b6f5f1e-3c4d-4e5f-8a9b-0c1d2e3f4a5b";

    private ContactBoardService boardService;
    private HttpServletRequest request;
    private ContactBoardController controller;

    @BeforeEach
    void setUp() {
        boardService = mock(ContactBoardService.class);
        RequestAuthority authority = mock(RequestAuthority.class);
        request = mock(HttpServletRequest.class);
        when(authority.contextOf(request)).thenReturn(SecurityContext.user("anna", TENANT, List.of()));
        controller = new ContactBoardController(boardService, authority);
    }

    private static ContactBoardService.ResolvedBoard board() {
        return new ContactBoardService.ResolvedBoard(PATH, new ContactBoard("Team", null, List.of()), List.of());
    }

    @Test
    void pinWithoutIdIsABadRequest() {
        assertThatThrownBy(() -> controller.pin(
                        TENANT, PROJECT, PATH, new PinContactRequest(null, null, null, null, null), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'uuid' is required");
        verifyNoInteractions(boardService);
    }

    @Test
    void updateWithoutIdIsABadRequest() {
        assertThatThrownBy(() ->
                        controller.update(TENANT, PROJECT, PATH, new UpdatePinRequest(" ", "x", null, null), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'uuid' is required");
        verifyNoInteractions(boardService);
    }

    @Test
    void pinCarriesTheVisibilityWarning() {
        when(boardService.pin(any(), eq(PATH), eq(ID), eq(ContactScope.USER), eq("Lead"), isNull(), eq(0)))
                .thenReturn(new ContactBoardService.PinResult(board(), "Only you can see this contact"));

        ContactBoardView view = controller.pin(
                TENANT, PROJECT, PATH, new PinContactRequest(ID, ContactScope.USER, "Lead", null, 0), request);

        assertThat(view.path()).isEqualTo(PATH);
        assertThat(view.title()).isEqualTo("Team");
        assertThat(view.warning()).isEqualTo("Only you can see this contact");
    }

    @Test
    void getDelegates() {
        when(boardService.get(any(), eq(PATH))).thenReturn(board());

        ContactBoardView view = controller.get(TENANT, PROJECT, PATH, request);

        assertThat(view.pins()).isEmpty();
        assertThat(view.warning()).isNull();
    }
}
