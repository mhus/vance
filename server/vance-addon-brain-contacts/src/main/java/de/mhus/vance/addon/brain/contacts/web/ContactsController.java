package de.mhus.vance.addon.brain.contacts.web;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.ContactSearch;
import de.mhus.vance.addon.brain.contacts.ContactService;
import de.mhus.vance.addon.brain.contacts.ContactVCardService;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST surface of the address book under {@code /brain/{tenant}/addon/contacts/…}
 * — convenience for the Web-UI; the LLM path is the {@code contact_*} tools.
 *
 * <p>No authorisation here beyond turning the request into a caller:
 * {@link ContactService} checks every scope itself (READ to look, WRITE to
 * change), because the scopes are three different projects and only the
 * service knows which one a call ends up in. {@code projectId} is optional —
 * without it the caller has the {@code user} and {@code tenant} scopes only.
 */
@RestController
@RequiredArgsConstructor
public class ContactsController {

    private static final String BASE = "/brain/{tenant}/addon/contacts";

    private final ContactService contactService;
    private final ContactVCardService vcardService;
    private final RequestAuthority authority;

    @GetMapping(BASE + "/contacts")
    public ContactListView list(
            @PathVariable String tenant,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String scope,
            @RequestParam(required = false) @Nullable String kind,
            @RequestParam(required = false) @Nullable String category,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        ContactSearch.Criteria criteria =
                new ContactSearch.Criteria(q, null, null, null, category, ContactKind.parseStrict(kind));
        List<ContactSummaryView> rows = contactService.search(caller, ContactScope.parse(scope), criteria).stream()
                .map(ContactSummaryView::of)
                .toList();
        List<ContactScopeView> scopes = contactService.scopes(caller).stream()
                .map(s -> new ContactScopeView(s.scope(), s.projectId(), s.writable()))
                .toList();
        List<String> broken = new ArrayList<>();
        contactService.broken(caller).forEach((s, paths) -> paths.forEach(p -> broken.add(s.wire() + ": " + p)));
        return new ContactListView(rows, scopes, contactService.defaultWriteScope(caller), broken);
    }

    @GetMapping(BASE + "/contact/{id}")
    public ContactView get(
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable String scope,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        return view(caller, contactService.get(caller, id, ContactScope.parse(scope)));
    }

    @PostMapping(BASE + "/contact")
    public ContactView create(
            @PathVariable String tenant,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestBody ContactWriteRequest body,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        return view(caller, contactService.create(caller, body.card(), body.scope()));
    }

    @PutMapping(BASE + "/contact/{id}")
    public ContactView replace(
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestBody ContactWriteRequest body,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        return view(caller, contactService.update(caller, id, body.scope(), current -> body.card()));
    }

    @DeleteMapping(BASE + "/contact/{id}")
    public DeleteContactView delete(
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable String scope,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        ContactService.GroupImpact impact = contactService.delete(caller, id, ContactScope.parse(scope));
        return new DeleteContactView(
                id, impact.groups().stream().map(ContactSummaryView::of).toList());
    }

    @PostMapping(BASE + "/contact/{id}/move")
    public MoveContactView move(
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestBody MoveContactRequest body,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        ContactService.MoveResult moved = contactService.move(caller, id, body.from(), body.to());
        return new MoveContactView(
                view(caller, moved.entry()),
                moved.strandedGroups().stream().map(ContactSummaryView::of).toList());
    }

    @PostMapping(BASE + "/contact/{id}/members")
    public ContactView changeMembers(
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestBody ChangeMembersRequest body,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        return view(caller, contactService.changeMembers(caller, id, body.scope(), body.add(), body.remove()));
    }

    @GetMapping(BASE + "/contact/{id}/photo")
    public ResponseEntity<byte[]> photo(
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable String scope,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        ContactService.PhotoContent photo = contactService.readPhoto(caller, id, ContactScope.parse(scope));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.mimeType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(photo.bytes());
    }

    @PutMapping(BASE + "/contact/{id}/photo")
    public ContactView setPhoto(
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable String scope,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request)
            throws IOException {
        ContactCaller caller = caller(tenant, projectId, request);
        return view(caller, contactService.setPhoto(caller, id, ContactScope.parse(scope), file.getBytes()));
    }

    @DeleteMapping(BASE + "/contact/{id}/photo")
    public ContactView removePhoto(
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable String scope,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        return view(caller, contactService.removePhoto(caller, id, ContactScope.parse(scope)));
    }

    @PostMapping(BASE + "/contact/{id}/gravatar")
    public GravatarView gravatar(
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable String scope,
            @RequestParam(required = false) @Nullable String email,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        Optional<ContactEntry> updated =
                contactService.setPhotoFromGravatar(caller, id, ContactScope.parse(scope), email);
        return updated.map(e -> new GravatarView(true, view(caller, e))).orElseGet(() -> new GravatarView(false, null));
    }

    @PostMapping(BASE + "/import")
    public ContactImportView importVcf(
            @PathVariable String tenant,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable String scope,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request)
            throws IOException {
        ContactCaller caller = caller(tenant, projectId, request);
        ContactVCardService.ImportReport report = vcardService.importVcf(
                caller, new String(file.getBytes(), StandardCharsets.UTF_8), ContactScope.parse(scope));
        return new ContactImportView(
                report.scope(),
                report.created(),
                report.updated(),
                report.skipped().stream().map(s -> s.name() + ": " + s.reason()).toList(),
                report.warnings());
    }

    /**
     * vCard 4.0 download. {@code ids} (comma list) wins; otherwise everything
     * the filters select — one scope, one category, or a group's members.
     */
    @GetMapping(BASE + "/export")
    public ResponseEntity<byte[]> exportVcf(
            @PathVariable String tenant,
            @RequestParam(required = false) @Nullable String projectId,
            @RequestParam(required = false) @Nullable String scope,
            @RequestParam(required = false) @Nullable String ids,
            @RequestParam(required = false) @Nullable String category,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        ContactScope only = ContactScope.parse(scope);
        List<ContactEntry> entries;
        if (ids != null && !ids.isBlank()) {
            entries = new ArrayList<>();
            for (String id : ids.split(",")) {
                if (!id.isBlank()) entries.add(contactService.get(caller, id.trim(), only));
            }
        } else {
            entries = contactService.search(
                    caller, only, new ContactSearch.Criteria(null, null, null, null, category, null));
        }
        byte[] body = vcardService.exportVcf(caller, entries).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/vcard; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contacts.vcf\"")
                .body(body);
    }

    /** What a {@code kind: contact} document shows — for the Cortex renderer. */
    @GetMapping(BASE + "/document")
    public ContactDocumentView document(
            @PathVariable String tenant,
            @RequestParam String projectId,
            @RequestParam String path,
            HttpServletRequest request) {
        ContactCaller caller = caller(tenant, projectId, request);
        ContactService.ResolvedDocument resolved = contactService.resolveDocument(caller, projectId, path);
        return new ContactDocumentView(
                resolved.card(),
                resolved.entry() == null ? null : ContactSummaryView.of(resolved.entry()),
                resolved.refId(),
                resolved.dangling());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private ContactCaller caller(String tenant, @Nullable String projectId, HttpServletRequest request) {
        return ContactCaller.of(
                tenant, authority.contextOf(request), AccessFilterBase.usernameOrNull(request), projectId);
    }

    private ContactView view(ContactCaller caller, ContactEntry e) {
        List<ContactMemberView> members = new ArrayList<>();
        if (e.card().kind() == ContactKind.GROUP) {
            contactService
                    .resolveMembers(caller, e)
                    .forEach((member, hit) -> members.add(
                            new ContactMemberView(member, hit == null ? null : ContactSummaryView.of(hit))));
        }
        List<ContactSummaryView> groups = contactService.groupsContaining(caller, e.id()).stream()
                .map(ContactSummaryView::of)
                .toList();
        return new ContactView(
                e.id(), e.scope(), e.projectId(), e.card(), e.photoPath() != null, e.photoVersion(), members, groups);
    }
}
