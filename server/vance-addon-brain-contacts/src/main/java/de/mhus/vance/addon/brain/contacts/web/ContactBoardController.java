package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.ContactBoardService;
import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of {@code kind: contact-board} documents under
 * {@code /brain/{tenant}/addon/contacts/board…}. Authorisation is the
 * board document's (READ to look, WRITE through {@code DocumentService} to
 * change); the pinned cards resolve with the reader's address-book rights.
 * Errors map through {@link ContactsErrorAdvice}.
 */
@RestController
@RequiredArgsConstructor
public class ContactBoardController {

    private static final String BASE = "/brain/{tenant}/addon/contacts/board";

    private final ContactBoardService boardService;
    private final RequestAuthority authority;

    @GetMapping(BASE)
    public ContactBoardView get(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String path,
            HttpServletRequest request) {
        return ContactBoardView.of(boardService.get(caller(tenant, projectId, request), path), null);
    }

    @PostMapping(BASE + "/pin")
    public ContactBoardView pin(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String path,
            @RequestBody PinContactRequest body,
            HttpServletRequest request) {
        ContactBoardService.PinResult result = boardService.pin(
                caller(tenant, projectId, request),
                path,
                body.uuid(),
                body.scope(),
                body.label(),
                body.note(),
                body.position());
        return ContactBoardView.of(result.board(), result.warning());
    }

    @PatchMapping(BASE + "/pin")
    public ContactBoardView update(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String path,
            @RequestBody UpdatePinRequest body,
            HttpServletRequest request) {
        return ContactBoardView.of(
                boardService.updatePin(
                        caller(tenant, projectId, request),
                        path,
                        body.uuid(),
                        body.label(),
                        body.note(),
                        body.position()),
                null);
    }

    @DeleteMapping(BASE + "/pin")
    public ContactBoardView unpin(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String path,
            @RequestParam String uuid,
            HttpServletRequest request) {
        return ContactBoardView.of(boardService.unpin(caller(tenant, projectId, request), path, uuid), null);
    }

    @PostMapping(BASE + "/reorder")
    public ContactBoardView reorder(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String path,
            @RequestBody ReorderPinsRequest body,
            HttpServletRequest request) {
        return ContactBoardView.of(boardService.reorder(caller(tenant, projectId, request), path, body.order()), null);
    }

    private ContactCaller caller(String tenant, String projectId, HttpServletRequest request) {
        return ContactCaller.of(
                tenant, authority.contextOf(request), AccessFilterBase.usernameOrNull(request), projectId);
    }
}
