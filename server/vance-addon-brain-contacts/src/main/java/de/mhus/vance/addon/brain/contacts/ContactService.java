package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactBody;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.addon.brain.contacts.model.ContactValue;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.project.ProjectService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * The address book. Owns every card under {@code _vance/contacts/} in the
 * three scope projects — the caller's hub ({@code user}), the working
 * project ({@code project}) and {@code _tenant} ({@code tenant}) — and is
 * the only code that reads or writes them for tools, REST and renderers.
 *
 * <p><b>Reads</b> merge the scopes in {@link ContactScope#READ_ORDER}; the
 * same id in a more specific scope shadows the broader one. A scope the
 * caller may not read is left out of a merged read and refused for an
 * explicit one.
 *
 * <p><b>Writes</b> go to exactly one scope (default {@code user}). The
 * service authorises them itself — {@code WRITE} on the scope's project —
 * and then writes as {@link WriteActor#system}, because {@code _vance/} is
 * a reserved prefix (permission R4) a plain writer cannot touch through the
 * generic document path. That is the point: cards change only through here.
 * Every path is built from a parsed UUID ({@link ContactIds}), never from
 * caller text, so the SYSTEM write cannot be steered elsewhere.
 *
 * <p><b>Changes</b> to a stored card are read-modify-write on the document
 * itself, never on the cached index entry: the card is read fresh, changed,
 * and written only if the document's version is still the one read —
 * otherwise {@link ContactConflictException} and nothing is written.
 */
@Service
@Slf4j
public class ContactService {

    private final DocumentService documentService;
    private final PermissionService permissionService;
    private final HomeBootstrapService homeBootstrapService;
    private final ContactIndex index;
    private final ContactPhotoProcessor photoProcessor;
    private final GravatarClient gravatarClient;

    public ContactService(
            DocumentService documentService,
            PermissionService permissionService,
            HomeBootstrapService homeBootstrapService,
            ContactIndex index,
            ContactPhotoProcessor photoProcessor,
            GravatarClient gravatarClient) {
        this.documentService = documentService;
        this.permissionService = permissionService;
        this.homeBootstrapService = homeBootstrapService;
        this.index = index;
        this.photoProcessor = photoProcessor;
        this.gravatarClient = gravatarClient;
    }

    /** The scope a write lands in when the caller names none. */
    public ContactScope defaultWriteScope(ContactCaller caller) {
        return caller.userLogin() != null ? ContactScope.USER : ContactScope.PROJECT;
    }

    /** A picture with its MIME type. */
    public record PhotoContent(byte[] bytes, String mimeType) {}

    /** What a delete or a move left behind in groups. */
    public record GroupImpact(List<ContactEntry> groups) {}

    /**
     * What a {@code kind: contact} document shows: the card, and — for a
     * reference — the address-book entry it resolved to ({@code null} when
     * the reference dangles).
     */
    public record ResolvedDocument(
            @Nullable ContactCard card,
            @Nullable ContactEntry entry,
            @Nullable String refId,
            boolean dangling) {}

    // ── Scopes ────────────────────────────────────────────────────

    /**
     * The project behind each scope the caller has, in read order. A scope
     * whose project equals a more specific one's is folded into it under its
     * canonical name: working in one's own hub, {@code project} <em>is</em>
     * {@code user}; working in {@code _tenant}, it is {@code tenant}.
     */
    Map<ContactScope, String> scopeProjects(ContactCaller caller) {
        Map<ContactScope, String> out = new LinkedHashMap<>();
        String hub = caller.userLogin() == null ? null : HomeBootstrapService.hubProjectName(caller.userLogin());
        String tenant = ProjectService.TENANT_PROJECT_NAME;
        String project = caller.projectId();
        if (project != null && !project.equals(hub) && !project.equals(tenant)) {
            out.put(ContactScope.PROJECT, project);
        }
        if (hub != null) out.put(ContactScope.USER, hub);
        out.put(ContactScope.TENANT, tenant);
        if (caller.confinedTo() != null) {
            out.values().removeIf(p -> !p.equals(caller.confinedTo()));
        }
        return out;
    }

    /** The project behind one scope, or a message why the caller has none. */
    public String projectOf(ContactCaller caller, ContactScope scope) {
        ContactScope canonical = canonical(caller, scope);
        String project = scopeProjects(caller).get(canonical);
        if (project != null) return project;
        throw new IllegalArgumentException(
                switch (scope) {
                    case USER -> "No user scope here — this call runs without a user";
                    case PROJECT -> "No project scope here — no project is selected";
                    case TENANT -> "The tenant scope is out of reach for this process";
                });
    }

    /** {@code project} in one's own hub is {@code user}; in {@code _tenant} it is {@code tenant}. */
    ContactScope canonical(ContactCaller caller, ContactScope scope) {
        if (scope != ContactScope.PROJECT || caller.projectId() == null) return scope;
        if (caller.projectId().equals(ProjectService.TENANT_PROJECT_NAME)) return ContactScope.TENANT;
        if (caller.userLogin() != null
                && caller.projectId().equals(HomeBootstrapService.hubProjectName(caller.userLogin()))) {
            return ContactScope.USER;
        }
        return scope;
    }

    /** A scope the caller can read, and whether they may write there. */
    public record ScopeInfo(ContactScope scope, String projectId, boolean writable) {}

    /** The caller's readable scopes in read order, each with its write right. */
    public List<ScopeInfo> scopes(ContactCaller caller) {
        List<ScopeInfo> out = new ArrayList<>();
        for (Map.Entry<ContactScope, String> e : readableScopes(caller, null).entrySet()) {
            boolean writable = permissionService.check(
                    caller.subject(), new Resource.Project(caller.tenantId(), e.getValue()), Action.WRITE);
            out.add(new ScopeInfo(e.getKey(), e.getValue(), writable));
        }
        return out;
    }

    // ── Reads ─────────────────────────────────────────────────────

    /**
     * Search the address book.
     *
     * @param only restrict to one scope; {@code null} = all readable scopes, merged
     */
    public List<ContactEntry> search(
            ContactCaller caller, @Nullable ContactScope only, ContactSearch.Criteria criteria) {
        return ContactSearch.search(entries(caller, only), criteria);
    }

    /** Every readable card, merged (or of one scope), unsorted. */
    public List<ContactEntry> entries(ContactCaller caller, @Nullable ContactScope only) {
        Map<String, ContactEntry> byId = new LinkedHashMap<>();
        for (Map.Entry<ContactScope, String> e : readableScopes(caller, only).entrySet()) {
            for (ContactEntry entry : index.entries(caller.tenantId(), e.getValue(), e.getKey())) {
                byId.putIfAbsent(entry.id(), entry);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Card files that did not parse, per readable scope — so "empty" is never a silent lie. */
    public Map<ContactScope, List<String>> broken(ContactCaller caller) {
        Map<ContactScope, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<ContactScope, String> e : readableScopes(caller, null).entrySet()) {
            List<String> paths = index.broken(caller.tenantId(), e.getValue());
            if (!paths.isEmpty()) out.put(e.getKey(), paths);
        }
        return out;
    }

    /**
     * One card by id.
     *
     * @param scope where to look; {@code null} = the read cascade, first hit wins
     */
    public ContactEntry get(ContactCaller caller, String rawId, @Nullable ContactScope scope) {
        String id = ContactIds.require(rawId);
        return find(caller, id, scope)
                .orElseThrow(() -> new ContactNotFoundException(
                        "No contact '" + id + "'" + (scope == null ? "" : " in scope " + scope.wire())));
    }

    public Optional<ContactEntry> find(ContactCaller caller, String id, @Nullable ContactScope scope) {
        for (Map.Entry<ContactScope, String> e : readableScopes(caller, scope).entrySet()) {
            ContactEntry hit = index.find(caller.tenantId(), e.getValue(), e.getKey(), id);
            if (hit != null) return Optional.of(hit);
        }
        return Optional.empty();
    }

    /**
     * Group members resolved for display: each id with the entry it resolved
     * to (in a scope at least as broad as the group's), or {@code null}.
     */
    public Map<String, @Nullable ContactEntry> resolveMembers(ContactCaller caller, ContactEntry group) {
        Map<String, @Nullable ContactEntry> out = new LinkedHashMap<>();
        for (String member : group.card().members()) {
            if (ContactIds.normalize(member) == null) {
                out.put(member, null);
                continue;
            }
            out.put(member, findVisibleFrom(caller, member, group.scope(), null).orElse(null));
        }
        return out;
    }

    /** Groups (readable to the caller) that list {@code id} as a member. */
    public List<ContactEntry> groupsContaining(ContactCaller caller, String id) {
        List<ContactEntry> out = new ArrayList<>();
        for (ContactEntry e : entries(caller, null)) {
            if (e.card().kind() == ContactKind.GROUP && e.card().members().contains(id)) out.add(e);
        }
        return out;
    }

    /**
     * What a {@code kind: contact} document at {@code path} in {@code projectId}
     * shows. A reference resolves through the caller's cascade; one into a
     * scope the caller has no right to read dangles like a deleted one — the
     * document must not tell the two apart.
     */
    public ResolvedDocument resolveDocument(ContactCaller caller, String projectId, String path) {
        permissionService.enforce(
                caller.subject(), new Resource.Document(caller.tenantId(), projectId, path), Action.READ);
        DocumentDocument doc = documentService
                .findByPath(caller.tenantId(), projectId, path)
                .orElseThrow(() -> new ContactNotFoundException("No document '" + path + "'"));
        ContactBody body = ContactCodec.parse(documentService.readContent(doc));
        if (body instanceof ContactBody.Inline inline) {
            String ownId = ContactIds.idOfCardPath(path);
            if (ownId != null) {
                ContactEntry entry = find(caller, ownId, null)
                        .filter(e -> e.projectId().equals(projectId))
                        .orElse(null);
                return new ResolvedDocument(inline.card(), entry, null, false);
            }
            return new ResolvedDocument(inline.card(), null, null, false);
        }
        ContactBody.Ref ref = (ContactBody.Ref) body;
        Optional<ContactEntry> hit;
        try {
            hit = find(caller, ref.uuid(), ref.scope());
        } catch (IllegalArgumentException | PermissionDeniedException e) {
            hit = Optional.empty();
        }
        return hit.map(e -> new ResolvedDocument(e.card(), e, ref.uuid(), false))
                .orElseGet(() -> new ResolvedDocument(null, null, ref.uuid(), true));
    }

    public PhotoContent readPhoto(ContactCaller caller, String rawId, @Nullable ContactScope scope) {
        ContactEntry entry = get(caller, rawId, scope);
        if (entry.photoPath() == null)
            throw new ContactNotFoundException("Contact '" + entry.id() + "' has no picture");
        DocumentDocument doc = documentService
                .findByPath(caller.tenantId(), entry.projectId(), entry.photoPath())
                .orElseThrow(() -> new ContactNotFoundException("Picture of '" + entry.id() + "' is gone"));
        return new PhotoContent(
                readBytes(doc, ContactPhotoProcessor.MAX_INPUT_BYTES),
                doc.getMimeType() == null ? "image/jpeg" : doc.getMimeType());
    }

    // ── Writes ────────────────────────────────────────────────────

    public ContactEntry create(ContactCaller caller, ContactCard card, @Nullable ContactScope scope) {
        ContactScope target = canonical(caller, scope == null ? defaultWriteScope(caller) : scope);
        String project = projectOf(caller, target);
        WriteActor actor = authorizeWrite(caller, project);
        ContactCard clean = card.toBuilder().photo(null).build();
        checkCard(caller, clean, target, null);
        String id = ContactIds.newId();
        documentService.createText(
                caller.tenantId(),
                project,
                ContactIds.cardPath(id),
                clean.displayName(),
                null,
                ContactCodec.serialize(withFullName(clean)),
                caller.userLogin(),
                actor);
        index.invalidate(caller.tenantId(), project);
        log.info("ContactService: created {} in {} ({})", id, target.wire(), project);
        return reload(caller, project, target, id);
    }

    /**
     * Change a card. {@code change} receives the stored card — read fresh
     * from its document, not from the cache — and returns the new one; the
     * picture field is the service's and is kept as stored.
     *
     * @param scope where the card must live; {@code null} = the default write
     *              scope. A card found only elsewhere is an error naming
     *              where it lives — never a silent write there.
     * @throws ContactConflictException when the card changed between the
     *         read and the write
     */
    public ContactEntry update(
            ContactCaller caller, String rawId, @Nullable ContactScope scope, UnaryOperator<ContactCard> change) {
        ContactEntry current = requireForWrite(caller, rawId, scope);
        WriteActor actor = authorizeWrite(caller, current.projectId());
        modify(
                caller,
                current,
                actor,
                null,
                true,
                stored -> change.apply(stored).toBuilder().photo(stored.photo()).build());
        return reload(caller, current.projectId(), current.scope(), current.id());
    }

    /** Delete a card and its picture. Returns the groups that still list it. */
    public GroupImpact delete(ContactCaller caller, String rawId, @Nullable ContactScope scope) {
        ContactEntry current = requireForWrite(caller, rawId, scope);
        WriteActor actor = authorizeWrite(caller, current.projectId());
        documentService.trash(current.documentId(), actor);
        if (current.photoPath() != null) trashIfPresent(caller, current.projectId(), current.photoPath(), actor);
        index.invalidate(caller.tenantId(), current.projectId());
        log.info(
                "ContactService: deleted {} from {}",
                current.id(),
                current.scope().wire());
        return new GroupImpact(groupsContaining(caller, current.id()));
    }

    /**
     * Move a card to another scope, picture included, keeping its id. Groups
     * in a broader scope than the new one can no longer resolve it — they are
     * reported, not edited.
     *
     * <p>All or nothing for the card: the copy in the target scope is written
     * first and the source is trashed last; when any step before that fails,
     * what was already written in the target is removed again, so a failed
     * move leaves neither an orphan picture nor a second card.
     */
    public MoveResult move(ContactCaller caller, String rawId, @Nullable ContactScope from, ContactScope to) {
        ContactEntry current = requireForWrite(caller, rawId, from);
        ContactScope target = canonical(caller, to);
        if (target == current.scope()) {
            throw new IllegalArgumentException("Contact '" + current.id() + "' is already in scope " + target.wire());
        }
        String targetProject = projectOf(caller, target);
        WriteActor sourceActor = authorizeWrite(caller, current.projectId());
        WriteActor targetActor = authorizeWrite(caller, targetProject);
        if (index.find(caller.tenantId(), targetProject, target, current.id()) != null) {
            throw new IllegalArgumentException("Scope " + target.wire() + " already holds a contact '" + current.id()
                    + "' — delete one of the two first");
        }
        Stored stored = stored(current);
        ContactCard card = stored.card();
        if (card.kind() == ContactKind.GROUP) {
            checkMembers(caller, card.members(), target, null);
        }
        @Nullable String targetPhotoPath = null;
        @Nullable String targetCardDocId = null;
        try {
            if (current.photoPath() != null) {
                Optional<DocumentDocument> photo =
                        documentService.findByPath(caller.tenantId(), current.projectId(), current.photoPath());
                if (photo.isPresent()) {
                    String ext = current.photoPath().endsWith(".png") ? "png" : "jpg";
                    targetPhotoPath = ContactIds.photoPath(current.id(), ext);
                    writePhotoDocument(
                            caller,
                            targetProject,
                            current.id(),
                            new ContactPhotoProcessor.Photo(
                                    readBytes(photo.get(), ContactPhotoProcessor.MAX_INPUT_BYTES),
                                    photo.get().getMimeType() == null
                                            ? "image/jpeg"
                                            : photo.get().getMimeType(),
                                    ext),
                            card.displayName(),
                            targetActor);
                } else {
                    card = card.toBuilder().photo(null).build();
                }
            }
            DocumentDocument created = documentService.createText(
                    caller.tenantId(),
                    targetProject,
                    ContactIds.cardPath(current.id()),
                    card.displayName(),
                    null,
                    ContactCodec.serialize(withFullName(card)),
                    caller.userLogin(),
                    targetActor);
            targetCardDocId = created.getId();
            requireVersion(current, stored.version());
            documentService.trash(current.documentId(), sourceActor);
        } catch (RuntimeException e) {
            rollbackMove(caller, targetProject, targetCardDocId, targetPhotoPath, targetActor, e);
            index.invalidate(caller.tenantId(), targetProject);
            throw e;
        }
        if (current.photoPath() != null) {
            try {
                trashIfPresent(caller, current.projectId(), current.photoPath(), sourceActor);
            } catch (RuntimeException e) {
                // The card has moved; what is left is an unreferenced picture file.
                log.warn(
                        "ContactService: moved {} but could not remove the old picture {}: {}",
                        current.id(),
                        current.photoPath(),
                        e.getMessage());
            }
        }
        index.invalidate(caller.tenantId(), current.projectId());
        index.invalidate(caller.tenantId(), targetProject);
        List<ContactEntry> stranded = new ArrayList<>();
        for (ContactEntry g : groupsContaining(caller, current.id())) {
            if (g.scope().breadth() > target.breadth()) stranded.add(g);
        }
        log.info(
                "ContactService: moved {} {} → {}",
                current.id(),
                current.scope().wire(),
                target.wire());
        return new MoveResult(reload(caller, targetProject, target, current.id()), stranded);
    }

    /** The moved card and the groups that can no longer resolve it. */
    public record MoveResult(ContactEntry entry, List<ContactEntry> strandedGroups) {}

    /** Add and remove group members in one step. */
    public ContactEntry changeMembers(
            ContactCaller caller,
            String rawGroupId,
            @Nullable ContactScope scope,
            List<String> add,
            List<String> remove) {
        ContactEntry group = requireForWrite(caller, rawGroupId, scope);
        if (group.card().kind() != ContactKind.GROUP) {
            throw new IllegalArgumentException("Contact '" + group.id() + "' is not a group");
        }
        List<String> removed = ContactCodec.memberList(remove);
        List<String> added = ContactCodec.memberList(add);
        if (added.contains(group.id())) throw new IllegalArgumentException("A group cannot contain itself");
        return update(caller, group.id(), group.scope(), card -> {
            Set<String> members = new LinkedHashSet<>(card.members());
            for (String r : removed) members.remove(r);
            members.addAll(added);
            return card.toBuilder().members(new ArrayList<>(members)).build();
        });
    }

    /** Store a picture (any common format; cropped and scaled here). */
    public ContactEntry setPhoto(ContactCaller caller, String rawId, @Nullable ContactScope scope, byte[] picture) {
        ContactEntry current = requireForWrite(caller, rawId, scope);
        WriteActor actor = authorizeWrite(caller, current.projectId());
        storePhoto(caller, current, picture, actor, null);
        return reload(caller, current.projectId(), current.scope(), current.id());
    }

    /** Take the picture from a document of the caller's working project. */
    public ContactEntry setPhotoFromDocument(
            ContactCaller caller, String rawId, @Nullable ContactScope scope, String documentPath) {
        String project = caller.projectId();
        if (project == null) throw new IllegalArgumentException("No project to take the picture from");
        permissionService.enforce(
                caller.subject(), new Resource.Document(caller.tenantId(), project, documentPath), Action.READ);
        DocumentDocument doc = documentService
                .findByPath(caller.tenantId(), project, documentPath)
                .orElseThrow(() -> new IllegalArgumentException("No document '" + documentPath + "' in " + project));
        if (doc.getSize() > ContactPhotoProcessor.MAX_INPUT_BYTES) throw tooLarge();
        return setPhoto(caller, rawId, scope, readBytes(doc, ContactPhotoProcessor.MAX_INPUT_BYTES));
    }

    /**
     * Look the card's e-mail up at Gravatar and keep the picture if there is
     * one. Explicit by design — see {@link GravatarClient}. Write access is
     * checked before Gravatar is asked, and only an address of the card
     * itself is looked up: every lookup tells Gravatar that someone holds it.
     *
     * @param email which of the card's addresses to ask for; {@code null} = the preferred one
     * @return the updated entry, or empty when Gravatar has no picture
     */
    public Optional<ContactEntry> setPhotoFromGravatar(
            ContactCaller caller, String rawId, @Nullable ContactScope scope, @Nullable String email) {
        ContactEntry current = requireForWrite(caller, rawId, scope);
        WriteActor actor = authorizeWrite(caller, current.projectId());
        String address;
        if (email != null && !email.isBlank()) {
            address = cardAddress(current.card(), email)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "'" + email.trim() + "' is not an e-mail address of contact '" + current.id() + "'"));
        } else {
            address = ContactCard.preferred(current.card().emails());
        }
        if (address == null) {
            throw new IllegalArgumentException("Contact '" + current.id() + "' has no e-mail address to look up");
        }
        Optional<byte[]> avatar = gravatarClient.fetch(address, photoProcessor.size());
        if (avatar.isEmpty()) return Optional.empty();
        storePhoto(caller, current, avatar.get(), actor, null);
        return Optional.of(reload(caller, current.projectId(), current.scope(), current.id()));
    }

    public ContactEntry removePhoto(ContactCaller caller, String rawId, @Nullable ContactScope scope) {
        ContactEntry current = requireForWrite(caller, rawId, scope);
        WriteActor actor = authorizeWrite(caller, current.projectId());
        if (current.photoPath() != null) trashIfPresent(caller, current.projectId(), current.photoPath(), actor);
        modify(
                caller,
                current,
                actor,
                null,
                false,
                c -> c.toBuilder().photo(null).build());
        return reload(caller, current.projectId(), current.scope(), current.id());
    }

    /**
     * Write a card under a given id — where a vCard {@code UID} that is a
     * UUID keeps its identity. Updates when the id exists in the target
     * scope, creates otherwise. For many cards at once use {@link #openBulk}.
     *
     * @return {@code true} when created, {@code false} when updated
     */
    public boolean upsert(ContactCaller caller, String rawId, ContactCard card, @Nullable ContactScope scope) {
        try (Bulk bulk = openBulk(caller, scope)) {
            return bulk.upsert(rawId, card);
        }
    }

    /**
     * The members of {@code members} a group in {@code groupScope} may hold —
     * URIs, and ids that resolve to a non-group card at least as broad as
     * the group. The import path uses it to keep what is valid and report
     * the rest instead of refusing the whole group.
     */
    public List<String> admissibleMembers(ContactCaller caller, List<String> members, ContactScope groupScope) {
        return admissible(caller, members, groupScope, null);
    }

    // ── Bulk writes ───────────────────────────────────────────────

    /**
     * Open a bulk write into one scope — the import path. Authorises once,
     * reads the scope once, keeps its own view of what it wrote (so group
     * members written earlier in the same run are visible to the group
     * check) and drops the cached index once, on {@link Bulk#close()} —
     * instead of a rebuild per card, which made large imports quadratic.
     */
    public Bulk openBulk(ContactCaller caller, @Nullable ContactScope scope) {
        ContactScope target = canonical(caller, scope == null ? defaultWriteScope(caller) : scope);
        String project = projectOf(caller, target);
        WriteActor actor = authorizeWrite(caller, project);
        // Start from the documents, not from a cache that may be up to a TTL old.
        index.invalidate(caller.tenantId(), project);
        Map<String, ContactEntry> known = new LinkedHashMap<>();
        for (ContactEntry e : index.entries(caller.tenantId(), project, target)) known.put(e.id(), e);
        return new Bulk(caller, target, project, actor, known);
    }

    /** A bulk write into one scope; close it to publish the writes to readers. */
    public final class Bulk implements AutoCloseable {

        private final ContactCaller caller;
        private final ContactScope scope;
        private final String project;
        private final WriteActor actor;
        private final Map<String, ContactEntry> known;

        private Bulk(
                ContactCaller caller,
                ContactScope scope,
                String project,
                WriteActor actor,
                Map<String, ContactEntry> known) {
            this.caller = caller;
            this.scope = scope;
            this.project = project;
            this.actor = actor;
            this.known = known;
        }

        public ContactScope scope() {
            return scope;
        }

        /** The cards of the target scope as they stand in this run. */
        public List<ContactEntry> entries() {
            return List.copyOf(known.values());
        }

        /** {@link ContactService#admissibleMembers}, counting the cards this run wrote. */
        public List<String> admissibleMembers(List<String> members) {
            return admissible(caller, members, scope, this);
        }

        /** {@link ContactService#upsert} without the per-card cache rebuild. */
        public boolean upsert(String rawId, ContactCard card) {
            return upsertInto(this, ContactIds.require(rawId), card);
        }

        /** {@link ContactService#setPhoto} for a card of the target scope. */
        public void setPhoto(String rawId, byte[] picture) {
            String id = ContactIds.require(rawId);
            ContactEntry entry = known.get(id);
            if (entry == null) throw new ContactNotFoundException("No contact '" + id + "' in scope " + scope.wire());
            known.put(id, storePhoto(caller, entry, picture, actor, this));
        }

        @Override
        public void close() {
            index.invalidate(caller.tenantId(), project);
        }
    }

    // ── Internals ─────────────────────────────────────────────────

    /** A card as its document holds it right now, with the version that was read. */
    private record Stored(ContactCard card, @Nullable Long version) {}

    private Stored stored(ContactEntry entry) {
        DocumentDocument doc = documentService
                .findById(entry.documentId())
                .orElseThrow(() -> new ContactConflictException(
                        "Contact '" + entry.id() + "' was removed meanwhile — read it again", null));
        return new Stored(ContactCodec.parseCard(documentService.readContent(doc)), doc.getVersion());
    }

    /**
     * Read-modify-write of a stored card: fresh read, {@code change}, then a
     * write only when the document is still at the version read. Unchanged
     * cards are not written. Does not touch the index — callers invalidate.
     *
     * @return the card as stored afterwards
     */
    private ContactCard modify(
            ContactCaller caller,
            ContactEntry entry,
            WriteActor actor,
            @Nullable Bulk bulk,
            boolean check,
            UnaryOperator<ContactCard> change) {
        Stored stored = stored(entry);
        ContactCard next = withFullName(change.apply(stored.card()));
        if (check) checkCard(caller, next, entry.scope(), bulk);
        if (next.equals(stored.card())) return next;
        requireVersion(entry, stored.version());
        try {
            documentService.update(
                    entry.documentId(), next.displayName(), null, ContactCodec.serialize(next), null, actor);
        } catch (OptimisticLockingFailureException e) {
            throw conflict(entry, e);
        }
        return next;
    }

    private void requireVersion(ContactEntry entry, @Nullable Long expected) {
        Optional<DocumentDocument> now = documentService.findById(entry.documentId());
        if (now.isEmpty() || !Objects.equals(now.get().getVersion(), expected)) throw conflict(entry, null);
    }

    private static ContactConflictException conflict(ContactEntry entry, @Nullable Throwable cause) {
        return new ContactConflictException(
                "Contact '" + entry.id() + "' was changed meanwhile — nothing written; read it again and retry", cause);
    }

    private boolean upsertInto(Bulk bulk, String id, ContactCard card) {
        ContactEntry existing = bulk.known.get(id);
        if (existing != null) {
            ContactCard written = modify(
                    bulk.caller,
                    existing,
                    bulk.actor,
                    bulk,
                    true,
                    stored -> card.toBuilder().photo(stored.photo()).build());
            bulk.known.put(id, withCard(existing, written));
            return false;
        }
        ContactCard clean = withFullName(card.toBuilder().photo(null).build());
        checkCard(bulk.caller, clean, bulk.scope, bulk);
        DocumentDocument doc = documentService.createText(
                bulk.caller.tenantId(),
                bulk.project,
                ContactIds.cardPath(id),
                clean.displayName(),
                null,
                ContactCodec.serialize(clean),
                bulk.caller.userLogin(),
                bulk.actor);
        if (doc.getId() == null) throw new IllegalStateException("Card document without id: " + id);
        bulk.known.put(id, new ContactEntry(id, bulk.scope, bulk.project, doc.getId(), clean, null, null));
        return true;
    }

    /**
     * Process and store a picture, retire a picture of the other format and
     * point the card at the new file. Does not touch the index.
     *
     * @return the entry as it stands afterwards
     */
    private ContactEntry storePhoto(
            ContactCaller caller, ContactEntry current, byte[] picture, WriteActor actor, @Nullable Bulk bulk) {
        ContactPhotoProcessor.Photo photo = photoProcessor.process(picture);
        String photoPath = ContactIds.photoPath(current.id(), photo.extension());
        String fileName = photoPath.substring(ContactIds.FOLDER.length());
        DocumentDocument written = writePhotoDocument(
                caller, current.projectId(), current.id(), photo, current.card().displayName(), actor);
        if (current.photoPath() != null && !current.photoPath().equals(photoPath)) {
            trashIfPresent(caller, current.projectId(), current.photoPath(), actor);
        }
        ContactCard card = modify(
                caller,
                current,
                actor,
                bulk,
                false,
                c -> fileName.equals(c.photo())
                        ? c
                        : c.toBuilder().photo(fileName).build());
        return new ContactEntry(
                current.id(),
                current.scope(),
                current.projectId(),
                current.documentId(),
                card,
                photoPath,
                written.getVersion());
    }

    private static ContactEntry withCard(ContactEntry e, ContactCard card) {
        return new ContactEntry(
                e.id(), e.scope(), e.projectId(), e.documentId(), card, e.photoPath(), e.photoVersion());
    }

    /** The card's own address matching {@code email} (trimmed, case-insensitive). */
    private static Optional<String> cardAddress(ContactCard card, String email) {
        String wanted = email.trim().toLowerCase(Locale.ROOT);
        for (ContactValue v : card.emails()) {
            if (v.value().trim().toLowerCase(Locale.ROOT).equals(wanted))
                return Optional.of(v.value().trim());
        }
        return Optional.empty();
    }

    private List<String> admissible(
            ContactCaller caller, List<String> members, ContactScope groupScope, @Nullable Bulk bulk) {
        List<String> out = new ArrayList<>();
        for (String member : members) {
            String id = ContactIds.normalize(member);
            if (id == null) {
                if (ContactIds.isUri(member)) out.add(member);
                continue;
            }
            Optional<ContactEntry> hit = findVisibleFrom(caller, id, groupScope, bulk);
            if (hit.isPresent() && hit.get().card().kind() != ContactKind.GROUP) out.add(id);
        }
        return out;
    }

    /** Undo the target half of a failed move; failures here are attached to the original one. */
    private void rollbackMove(
            ContactCaller caller,
            String targetProject,
            @Nullable String cardDocId,
            @Nullable String photoPath,
            WriteActor actor,
            RuntimeException cause) {
        if (cardDocId != null) {
            try {
                documentService.delete(cardDocId, actor);
            } catch (RuntimeException e) {
                cause.addSuppressed(e);
            }
        }
        if (photoPath != null) {
            try {
                documentService
                        .findByPath(caller.tenantId(), targetProject, photoPath)
                        .ifPresent(doc -> documentService.delete(doc.getId(), actor));
            } catch (RuntimeException e) {
                cause.addSuppressed(e);
            }
        }
        log.warn("ContactService: move into {} failed and was rolled back: {}", targetProject, cause.getMessage());
    }

    /** Readable scopes (merged read) or the one asked for (enforced). */
    private Map<ContactScope, String> readableScopes(ContactCaller caller, @Nullable ContactScope only) {
        Map<ContactScope, String> all = scopeProjects(caller);
        if (only != null) {
            ContactScope canonical = canonical(caller, only);
            String project = projectOf(caller, canonical);
            permissionService.enforce(caller.subject(), new Resource.Project(caller.tenantId(), project), Action.READ);
            return Map.of(canonical, project);
        }
        Map<ContactScope, String> out = new LinkedHashMap<>();
        for (Map.Entry<ContactScope, String> e : all.entrySet()) {
            if (permissionService.check(
                    caller.subject(), new Resource.Project(caller.tenantId(), e.getValue()), Action.READ)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * A card visible from a group in {@code groupScope}. During a bulk write
     * the bulk's own view stands in for the (not yet refreshed) index of its
     * target project.
     */
    private Optional<ContactEntry> findVisibleFrom(
            ContactCaller caller, String id, ContactScope groupScope, @Nullable Bulk bulk) {
        for (Map.Entry<ContactScope, String> e : readableScopes(caller, null).entrySet()) {
            if (e.getKey().breadth() < groupScope.breadth()) continue;
            ContactEntry hit = bulk != null && bulk.project.equals(e.getValue())
                    ? bulk.known.get(id)
                    : index.find(caller.tenantId(), e.getValue(), e.getKey(), id);
            if (hit != null) return Optional.of(hit);
        }
        return Optional.empty();
    }

    private ContactEntry requireForWrite(ContactCaller caller, String rawId, @Nullable ContactScope scope) {
        String id = ContactIds.require(rawId);
        ContactScope target = canonical(caller, scope == null ? defaultWriteScope(caller) : scope);
        String project = projectOf(caller, target);
        ContactEntry hit = index.find(caller.tenantId(), project, target, id);
        if (hit != null) return hit;
        Optional<ContactEntry> elsewhere = find(caller, id, null);
        if (elsewhere.isPresent()) {
            throw new ContactNotFoundException("Contact '" + id + "' is not in scope " + target.wire()
                    + " — it lives in " + elsewhere.get().scope().wire() + "; pass scope: "
                    + elsewhere.get().scope().wire());
        }
        throw new ContactNotFoundException("No contact '" + id + "'");
    }

    private WriteActor authorizeWrite(ContactCaller caller, String project) {
        permissionService.enforce(caller.subject(), new Resource.Project(caller.tenantId(), project), Action.WRITE);
        if (caller.userLogin() != null && project.equals(HomeBootstrapService.hubProjectName(caller.userLogin()))) {
            homeBootstrapService.ensureHome(caller.tenantId(), caller.userLogin());
        }
        return WriteActor.system(caller.subject());
    }

    /** The invariants every stored card satisfies. */
    private void checkCard(ContactCaller caller, ContactCard card, ContactScope scope, @Nullable Bulk bulk) {
        if (card.displayName().isEmpty()) {
            throw new IllegalArgumentException("A contact needs a name — fullName, name or org.name");
        }
        if (card.kind() == ContactKind.GROUP) {
            checkMembers(caller, card.members(), scope, bulk);
        } else if (!card.members().isEmpty()) {
            throw new IllegalArgumentException("Only a group (kind: group) has members");
        }
        if (card.org() != null
                && card.org().ref() != null
                && ContactIds.normalize(card.org().ref()) == null) {
            throw new IllegalArgumentException("org.ref must be the UUID of an org contact");
        }
    }

    /**
     * Members must resolve in a scope at least as broad as the group's;
     * otherwise another reader of the group would meet a member they cannot
     * see — or the name of someone's private contact.
     */
    private void checkMembers(
            ContactCaller caller, List<String> members, ContactScope groupScope, @Nullable Bulk bulk) {
        for (String member : members) {
            String id = ContactIds.normalize(member);
            if (id == null) {
                if (!ContactIds.isUri(member)) {
                    throw new IllegalArgumentException(
                            "Member '" + member + "' is neither a contact id nor a URI (mailto:…)");
                }
                continue;
            }
            Optional<ContactEntry> hit = findVisibleFrom(caller, id, groupScope, bulk);
            if (hit.isEmpty()) {
                throw new IllegalArgumentException("Member '" + id + "' is unknown or not visible from a "
                        + groupScope.wire() + " group — a group may only hold contacts of its own or a broader scope");
            }
            if (hit.get().card().kind() == ContactKind.GROUP) {
                throw new IllegalArgumentException("Member '" + id + "' is a group — groups do not nest");
            }
        }
    }

    /** Store the full name explicitly, so the file reads the same without the model's fallback. */
    private static ContactCard withFullName(ContactCard card) {
        return card.fullName().isEmpty()
                ? card.toBuilder().fullName(card.displayName()).build()
                : card;
    }

    /** Writes the picture document and returns it. */
    private DocumentDocument writePhotoDocument(
            ContactCaller caller,
            String project,
            String id,
            ContactPhotoProcessor.Photo photo,
            String title,
            WriteActor actor) {
        return documentService.createOrReplaceBinary(
                caller.tenantId(),
                project,
                ContactIds.photoPath(id, photo.extension()),
                photo.bytes(),
                photo.mimeType(),
                title,
                null,
                null,
                caller.userLogin(),
                actor);
    }

    private void trashIfPresent(ContactCaller caller, String project, String path, WriteActor actor) {
        documentService
                .findByPath(caller.tenantId(), project, path)
                .ifPresent(doc -> documentService.trash(doc.getId(), actor));
    }

    private ContactEntry reload(ContactCaller caller, String project, ContactScope scope, String id) {
        index.invalidate(caller.tenantId(), project);
        ContactEntry entry = index.find(caller.tenantId(), project, scope, id);
        if (entry == null) throw new IllegalStateException("Contact '" + id + "' vanished right after the write");
        return entry;
    }

    /** The document's bytes, refused once they pass {@code limit} — the size field is not trusted alone. */
    private byte[] readBytes(DocumentDocument doc, int limit) {
        try (InputStream in = documentService.loadContent(doc)) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) throw tooLarge();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read '" + doc.getPath() + "'", e);
        }
    }

    private static IllegalArgumentException tooLarge() {
        return new IllegalArgumentException(
                "The picture is larger than " + (ContactPhotoProcessor.MAX_INPUT_BYTES / (1024 * 1024)) + " MB");
    }
}
