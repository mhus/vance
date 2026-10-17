package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactBody;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.Action;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
            out.put(member, findVisibleFrom(caller, member, group.scope()).orElse(null));
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
     * shows. A reference resolves through the caller's cascade.
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
        } catch (IllegalArgumentException e) {
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
        return new PhotoContent(readBytes(doc), doc.getMimeType() == null ? "image/jpeg" : doc.getMimeType());
    }

    // ── Writes ────────────────────────────────────────────────────

    public ContactEntry create(ContactCaller caller, ContactCard card, @Nullable ContactScope scope) {
        ContactScope target = canonical(caller, scope == null ? defaultWriteScope(caller) : scope);
        String project = projectOf(caller, target);
        WriteActor actor = authorizeWrite(caller, project);
        ContactCard clean = card.toBuilder().photo(null).build();
        checkCard(caller, clean, target);
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
     * Change a card. {@code change} receives the stored card and returns the
     * new one; the picture field is the service's and is kept as stored.
     *
     * @param scope where the card must live; {@code null} = the default write
     *              scope. A card found only elsewhere is an error naming
     *              where it lives — never a silent write there.
     */
    public ContactEntry update(
            ContactCaller caller, String rawId, @Nullable ContactScope scope, UnaryOperator<ContactCard> change) {
        ContactEntry current = requireForWrite(caller, rawId, scope);
        WriteActor actor = authorizeWrite(caller, current.projectId());
        ContactCard next = change.apply(current.card()).toBuilder()
                .photo(current.card().photo())
                .build();
        checkCard(caller, next, current.scope());
        writeCard(caller, current, next, actor);
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
        if (current.card().kind() == ContactKind.GROUP) {
            checkMembers(caller, current.card().members(), target);
        }
        ContactCard card = current.card();
        if (current.photoPath() != null) {
            Optional<DocumentDocument> photo =
                    documentService.findByPath(caller.tenantId(), current.projectId(), current.photoPath());
            if (photo.isPresent()) {
                String ext = current.photoPath().endsWith(".png") ? "png" : "jpg";
                writePhotoDocument(
                        caller,
                        targetProject,
                        current.id(),
                        new ContactPhotoProcessor.Photo(
                                readBytes(photo.get()),
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
        documentService.createText(
                caller.tenantId(),
                targetProject,
                ContactIds.cardPath(current.id()),
                card.displayName(),
                null,
                ContactCodec.serialize(withFullName(card)),
                caller.userLogin(),
                targetActor);
        documentService.trash(current.documentId(), sourceActor);
        if (current.photoPath() != null) trashIfPresent(caller, current.projectId(), current.photoPath(), sourceActor);
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
        Set<String> members = new LinkedHashSet<>(group.card().members());
        for (String r : ContactCodec.memberList(remove)) members.remove(r);
        List<String> added = ContactCodec.memberList(add);
        if (added.contains(group.id())) throw new IllegalArgumentException("A group cannot contain itself");
        members.addAll(added);
        return update(
                caller,
                group.id(),
                group.scope(),
                card -> card.toBuilder().members(new ArrayList<>(members)).build());
    }

    /** Store a picture (any common format; cropped and scaled here). */
    public ContactEntry setPhoto(ContactCaller caller, String rawId, @Nullable ContactScope scope, byte[] picture) {
        ContactEntry current = requireForWrite(caller, rawId, scope);
        WriteActor actor = authorizeWrite(caller, current.projectId());
        ContactPhotoProcessor.Photo photo = photoProcessor.process(picture);
        String fileName = writePhotoDocument(
                caller, current.projectId(), current.id(), photo, current.card().displayName(), actor);
        if (current.photoPath() != null && !current.photoPath().endsWith("/" + fileName)) {
            trashIfPresent(caller, current.projectId(), current.photoPath(), actor);
        }
        if (!fileName.equals(current.card().photo())) {
            writeCard(
                    caller, current, current.card().toBuilder().photo(fileName).build(), actor);
        } else {
            index.invalidate(caller.tenantId(), current.projectId());
        }
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
        return setPhoto(caller, rawId, scope, readBytes(doc));
    }

    /**
     * Look the card's e-mail up at Gravatar and keep the picture if there is
     * one. Explicit by design — see {@link GravatarClient}.
     *
     * @param email which address to ask for; {@code null} = the preferred one
     * @return the updated entry, or empty when Gravatar has no picture
     */
    public Optional<ContactEntry> setPhotoFromGravatar(
            ContactCaller caller, String rawId, @Nullable ContactScope scope, @Nullable String email) {
        ContactEntry current = requireForWrite(caller, rawId, scope);
        String address = email != null && !email.isBlank()
                ? email.trim()
                : ContactCard.preferred(current.card().emails());
        if (address == null) {
            throw new IllegalArgumentException("Contact '" + current.id() + "' has no e-mail address to look up");
        }
        Optional<byte[]> avatar = gravatarClient.fetch(address, photoProcessor.size());
        if (avatar.isEmpty()) return Optional.empty();
        return Optional.of(setPhoto(caller, current.id(), current.scope(), avatar.get()));
    }

    public ContactEntry removePhoto(ContactCaller caller, String rawId, @Nullable ContactScope scope) {
        ContactEntry current = requireForWrite(caller, rawId, scope);
        WriteActor actor = authorizeWrite(caller, current.projectId());
        if (current.photoPath() != null) trashIfPresent(caller, current.projectId(), current.photoPath(), actor);
        if (current.card().photo() != null) {
            writeCard(caller, current, current.card().toBuilder().photo(null).build(), actor);
        }
        return reload(caller, current.projectId(), current.scope(), current.id());
    }

    /**
     * Write a card under a given id — the import path, where a vCard
     * {@code UID} that is a UUID keeps its identity. Updates when the id
     * exists in the target scope, creates otherwise.
     *
     * @return {@code true} when created, {@code false} when updated
     */
    public boolean upsert(ContactCaller caller, String rawId, ContactCard card, @Nullable ContactScope scope) {
        String id = ContactIds.require(rawId);
        ContactScope target = canonical(caller, scope == null ? defaultWriteScope(caller) : scope);
        String project = projectOf(caller, target);
        WriteActor actor = authorizeWrite(caller, project);
        ContactEntry existing = index.find(caller.tenantId(), project, target, id);
        ContactCard clean = card.toBuilder()
                .photo(existing == null ? null : existing.card().photo())
                .build();
        checkCard(caller, clean, target);
        if (existing != null) {
            writeCard(caller, existing, clean, actor);
            return false;
        }
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
        return true;
    }

    /**
     * The members of {@code members} a group in {@code groupScope} may hold —
     * URIs, and ids that resolve to a non-group card at least as broad as
     * the group. The import path uses it to keep what is valid and report
     * the rest instead of refusing the whole group.
     */
    public List<String> admissibleMembers(ContactCaller caller, List<String> members, ContactScope groupScope) {
        List<String> out = new ArrayList<>();
        for (String member : members) {
            String id = ContactIds.normalize(member);
            if (id == null) {
                if (ContactIds.isUri(member)) out.add(member);
                continue;
            }
            Optional<ContactEntry> hit = findVisibleFrom(caller, id, groupScope);
            if (hit.isPresent() && hit.get().card().kind() != ContactKind.GROUP) out.add(id);
        }
        return out;
    }

    // ── Internals ─────────────────────────────────────────────────

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

    private Optional<ContactEntry> findVisibleFrom(ContactCaller caller, String id, ContactScope groupScope) {
        for (Map.Entry<ContactScope, String> e : readableScopes(caller, null).entrySet()) {
            if (e.getKey().breadth() < groupScope.breadth()) continue;
            ContactEntry hit = index.find(caller.tenantId(), e.getValue(), e.getKey(), id);
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
    private void checkCard(ContactCaller caller, ContactCard card, ContactScope scope) {
        if (card.displayName().isEmpty()) {
            throw new IllegalArgumentException("A contact needs a name — fullName, name or org.name");
        }
        if (card.kind() == ContactKind.GROUP) {
            checkMembers(caller, card.members(), scope);
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
    private void checkMembers(ContactCaller caller, List<String> members, ContactScope groupScope) {
        for (String member : members) {
            String id = ContactIds.normalize(member);
            if (id == null) {
                if (!ContactIds.isUri(member)) {
                    throw new IllegalArgumentException(
                            "Member '" + member + "' is neither a contact id nor a URI (mailto:…)");
                }
                continue;
            }
            Optional<ContactEntry> hit = findVisibleFrom(caller, id, groupScope);
            if (hit.isEmpty()) {
                throw new IllegalArgumentException("Member '" + id + "' is unknown or not visible from a "
                        + groupScope.wire() + " group — a group may only hold contacts of its own or a broader scope");
            }
            if (hit.get().card().kind() == ContactKind.GROUP) {
                throw new IllegalArgumentException("Member '" + id + "' is a group — groups do not nest");
            }
        }
    }

    private void writeCard(ContactCaller caller, ContactEntry current, ContactCard next, WriteActor actor) {
        documentService.update(
                current.documentId(),
                next.displayName(),
                null,
                ContactCodec.serialize(withFullName(next)),
                null,
                actor);
        index.invalidate(caller.tenantId(), current.projectId());
    }

    /** Store the full name explicitly, so the file reads the same without the model's fallback. */
    private static ContactCard withFullName(ContactCard card) {
        return card.fullName().isEmpty()
                ? card.toBuilder().fullName(card.displayName()).build()
                : card;
    }

    /** Writes the picture document and returns its file name. */
    private String writePhotoDocument(
            ContactCaller caller,
            String project,
            String id,
            ContactPhotoProcessor.Photo photo,
            String title,
            WriteActor actor) {
        String path = ContactIds.photoPath(id, photo.extension());
        documentService.createOrReplaceBinary(
                caller.tenantId(),
                project,
                path,
                photo.bytes(),
                photo.mimeType(),
                title,
                null,
                null,
                caller.userLogin(),
                actor);
        return path.substring(ContactIds.FOLDER.length());
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

    private byte[] readBytes(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read '" + doc.getPath() + "'", e);
        }
    }
}
