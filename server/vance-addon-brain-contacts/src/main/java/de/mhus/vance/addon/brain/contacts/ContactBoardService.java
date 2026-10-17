package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactBoard;
import de.mhus.vance.addon.brain.contacts.model.ContactPin;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.project.ProjectService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@code kind: contact-board} documents: a curated list of pinned contacts
 * with a heading and a note each.
 *
 * <p>Unlike the address book, a board lives wherever its author put it —
 * the path comes from the caller — so writes are ordinary user writes
 * ({@link WriteActor#user}) that {@code DocumentService} authorises like any
 * document edit. Pins are references: a pinned card always shows the current
 * address-book state, resolved through the <em>reader's</em> cascade. A pin
 * the reader cannot resolve (deleted, or someone else's private contact)
 * keeps its heading and note — they are the board's own content.
 */
@Service
@Slf4j
public class ContactBoardService {

    private final DocumentService documentService;
    private final PermissionService permissionService;
    private final ContactService contactService;

    public ContactBoardService(
            DocumentService documentService, PermissionService permissionService, ContactService contactService) {
        this.documentService = documentService;
        this.permissionService = permissionService;
        this.contactService = contactService;
    }

    /** A pin with what it resolved to for this reader ({@code entry == null}: not available). */
    public record ResolvedPin(ContactPin pin, @Nullable ContactEntry entry) {}

    /** A board as one reader sees it. */
    public record ResolvedBoard(String path, ContactBoard board, List<ResolvedPin> pins) {}

    /**
     * The result of a pin: the board, and why other readers may not see the
     * new card (empty when they will).
     */
    public record PinResult(ResolvedBoard board, @Nullable String warning) {}

    public ResolvedBoard get(ContactCaller caller, String path) {
        return resolve(caller, path, load(caller, path).board());
    }

    /** Create an empty board at {@code path} (in the caller's project). */
    public ResolvedBoard create(ContactCaller caller, String path, @Nullable String title) {
        String project = requireProject(caller);
        ContactBoard board = new ContactBoard(title, null, List.of());
        documentService.createText(
                caller.tenantId(),
                project,
                path,
                title,
                null,
                ContactBoardCodec.serialize(board),
                caller.userLogin(),
                WriteActor.user(caller.subject()));
        return resolve(caller, path, board);
    }

    /**
     * Pin a contact. It must be visible to the caller; the scope it was found
     * in is stored, so the pin resolves the same way for every reader.
     *
     * @param position 0-based insert position; {@code null} = at the end
     */
    public PinResult pin(
            ContactCaller caller,
            String path,
            String rawId,
            @Nullable ContactScope scope,
            @Nullable String label,
            @Nullable String note,
            @Nullable Integer position) {
        ContactEntry contact = contactService.get(caller, rawId, scope);
        Loaded loaded = load(caller, path);
        if (loaded.board().pins().stream().anyMatch(p -> p.uuid().equals(contact.id()))) {
            throw new IllegalArgumentException(
                    "'" + contact.card().displayName() + "' is already pinned on this board");
        }
        List<ContactPin> pins = new ArrayList<>(loaded.board().pins());
        ContactPin pin = new ContactPin(contact.id(), contact.scope(), label, note);
        pins.add(clamp(position, pins.size()), pin);
        ResolvedBoard saved = save(caller, loaded, loaded.board().withPins(pins));
        return new PinResult(saved, visibilityWarning(caller, contact));
    }

    public ResolvedBoard unpin(ContactCaller caller, String path, String rawId) {
        String id = ContactIds.require(rawId);
        return change(caller, path, pins -> {
            List<ContactPin> next = new ArrayList<>(pins);
            if (!next.removeIf(p -> p.uuid().equals(id))) {
                throw new ContactNotFoundException("Contact '" + id + "' is not pinned on this board");
            }
            return next;
        });
    }

    /**
     * Change a pin's heading, note or position. {@code null} = unchanged,
     * {@code ""} = clear (heading, note).
     */
    public ResolvedBoard updatePin(
            ContactCaller caller,
            String path,
            String rawId,
            @Nullable String label,
            @Nullable String note,
            @Nullable Integer position) {
        String id = ContactIds.require(rawId);
        return change(caller, path, pins -> {
            List<ContactPin> next = new ArrayList<>(pins);
            int index = indexOf(next, id);
            ContactPin pin = next.remove(index);
            if (label != null) pin = pin.withLabel(label);
            if (note != null) pin = pin.withNote(note);
            next.add(position == null ? index : clamp(position, next.size()), pin);
            return next;
        });
    }

    /**
     * Put the pins into this order. Ids not on the board are an error; pins
     * the order leaves out keep their relative order at the end — a client
     * that missed a concurrent pin does not delete it by reordering.
     */
    public ResolvedBoard reorder(ContactCaller caller, String path, List<String> order) {
        return change(caller, path, pins -> {
            Map<String, ContactPin> byId = new LinkedHashMap<>();
            for (ContactPin p : pins) byId.put(p.uuid(), p);
            List<ContactPin> next = new ArrayList<>();
            for (String raw : order) {
                String id = ContactIds.require(raw);
                ContactPin p = byId.remove(id);
                if (p == null) throw new IllegalArgumentException("Contact '" + id + "' is not pinned on this board");
                next.add(p);
            }
            next.addAll(byId.values());
            return next;
        });
    }

    // ── Internals ─────────────────────────────────────────────────

    private record Loaded(DocumentDocument doc, ContactBoard board) {}

    private Loaded load(ContactCaller caller, String path) {
        String project = requireProject(caller);
        permissionService.enforce(
                caller.subject(), new Resource.Document(caller.tenantId(), project, path), Action.READ);
        DocumentDocument doc = documentService
                .findByPath(caller.tenantId(), project, path)
                .orElseThrow(() -> new ContactNotFoundException("No document '" + path + "' in " + project));
        if (!ContactBoardCodec.KIND.equals(doc.getKind())) {
            throw new IllegalArgumentException("'" + path + "' is not a contact-board document");
        }
        return new Loaded(doc, ContactBoardCodec.parse(documentService.readContent(doc)));
    }

    private ResolvedBoard change(ContactCaller caller, String path, UnaryOperator<List<ContactPin>> edit) {
        Loaded loaded = load(caller, path);
        return save(
                caller,
                loaded,
                loaded.board().withPins(edit.apply(loaded.board().pins())));
    }

    private ResolvedBoard save(ContactCaller caller, Loaded loaded, ContactBoard next) {
        String id = loaded.doc().getId();
        if (id == null) throw new IllegalStateException("Board document without id");
        documentService.update(
                id, null, null, ContactBoardCodec.serialize(next), null, WriteActor.user(caller.subject()));
        log.trace(
                "ContactBoardService: saved {} ({} pins)",
                loaded.doc().getPath(),
                next.pins().size());
        return resolve(caller, loaded.doc().getPath(), next);
    }

    private ResolvedBoard resolve(ContactCaller caller, String path, ContactBoard board) {
        List<ResolvedPin> out = new ArrayList<>();
        for (ContactPin pin : board.pins()) {
            Optional<ContactEntry> hit;
            try {
                hit = contactService.find(caller, pin.uuid(), pin.scope());
            } catch (IllegalArgumentException | de.mhus.vance.shared.permission.PermissionDeniedException e) {
                // The pin's scope is one this reader does not have (no user, a
                // scope out of reach) — not available, not an error.
                hit = Optional.empty();
            }
            out.add(new ResolvedPin(pin, hit.orElse(null)));
        }
        return new ResolvedBoard(path, board, out);
    }

    /**
     * Who reads the board versus who sees the contact: a board in a project
     * is read by the project, one in {@code _tenant} by the tenant, one in
     * the hub by its owner. A narrower contact is invisible to the rest.
     */
    private @Nullable String visibilityWarning(ContactCaller caller, ContactEntry contact) {
        String project = requireProject(caller);
        int audience;
        if (project.equals(ProjectService.TENANT_PROJECT_NAME)) audience = ContactScope.TENANT.breadth();
        else if (caller.userLogin() != null
                && project.equals(HomeBootstrapService.hubProjectName(caller.userLogin()))) {
            audience = ContactScope.USER.breadth();
        } else audience = ContactScope.PROJECT.breadth();
        if (contact.scope().breadth() >= audience) return null;
        return "'" + contact.card().displayName() + "' is a " + contact.scope().wire()
                + " contact — other readers of this board see the heading and the note, not the card. "
                + "Move the contact to a broader scope to share it.";
    }

    private static String requireProject(ContactCaller caller) {
        if (caller.projectId() == null)
            throw new IllegalArgumentException("A contact board lives in a project — none given");
        return caller.projectId();
    }

    private static int indexOf(List<ContactPin> pins, String id) {
        for (int i = 0; i < pins.size(); i++) {
            if (pins.get(i).uuid().equals(id)) return i;
        }
        throw new ContactNotFoundException("Contact '" + id + "' is not pinned on this board");
    }

    private static int clamp(@Nullable Integer position, int size) {
        if (position == null) return size;
        return Math.max(0, Math.min(position, size));
    }
}
