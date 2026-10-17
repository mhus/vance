package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactCodec;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.ContactNotFoundException;
import de.mhus.vance.addon.brain.contacts.ContactService;
import de.mhus.vance.addon.brain.contacts.GravatarUnavailableException;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shared plumbing of the {@code contact_*} tools: turning a tool call into a
 * {@link ContactCaller}, reading params, shaping results, and translating the
 * service's refusals into {@link ToolException}s the model can read.
 */
@Component
public class ContactToolSupport {

    /** The JSON-schema fragment for the {@code scope} parameter. */
    static final Map<String, Object> SCOPE_PARAM = Map.of(
            "type",
            "string",
            "enum",
            List.of("user", "project", "tenant"),
            "description",
            "user = the caller's private contacts, project = this project's, tenant = shared by everyone in the tenant.");

    private final EddieContext eddieContext;
    private final SecurityContextFactory contextFactory;
    private final ContactService contactService;

    public ContactToolSupport(
            EddieContext eddieContext, SecurityContextFactory contextFactory, ContactService contactService) {
        this.eddieContext = eddieContext;
        this.contextFactory = contextFactory;
        this.contactService = contactService;
    }

    /**
     * The caller of a tool call. The project is resolved the way every
     * project-addressed tool resolves it (READ enforced there); a call
     * without any project still has the {@code user} and {@code tenant}
     * scopes. A confined process sees only scopes of its own project.
     */
    ContactCaller caller(Map<String, Object> params, ToolInvocationContext ctx) {
        SecurityContext subject = contextFactory.forToolSubject(ctx.tenantId(), ctx.userId());
        String projectId = null;
        try {
            ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
            projectId = project.getName();
        } catch (ToolException e) {
            if (str(params, "projectId") != null) throw e;
        }
        ContactCaller caller = ContactCaller.of(ctx.tenantId(), subject, ctx.userId(), projectId);
        if (eddieContext.isConfined(ctx)) {
            String own = ctx.projectId();
            if (own == null) throw new ToolException("Confined process without a project");
            caller = caller.confined(own);
        }
        return caller;
    }

    /** The resolved working project, required (export/import address documents in it). */
    ProjectDocument project(Map<String, Object> params, ToolInvocationContext ctx) {
        return eddieContext.resolveProject(params, ctx, false);
    }

    SecurityContext subject(ToolInvocationContext ctx) {
        return contextFactory.forToolSubject(ctx.tenantId(), ctx.userId());
    }

    ContactService service() {
        return contactService;
    }

    /** Run a service call; refusals become tool errors with the service's sentence. */
    static <T> T run(Supplier<T> call) {
        try {
            return call.get();
        } catch (ContactNotFoundException | GravatarUnavailableException | IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        } catch (de.mhus.vance.shared.document.kind.KindCodecException e) {
            throw new ToolException("Unreadable contact data: " + e.getMessage(), e);
        }
    }

    // ── Params ────────────────────────────────────────────────────

    static @Nullable String str(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    static String requireStr(Map<String, Object> params, String key) {
        String v = str(params, key);
        if (v == null) throw new ToolException(key + " is required");
        return v;
    }

    static @Nullable ContactScope scope(Map<String, Object> params, String key) {
        try {
            return ContactScope.parse(str(params, key));
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    static @Nullable ContactKind kind(Map<String, Object> params) {
        try {
            return ContactKind.parseStrict(str(params, "kind"));
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    static List<String> strList(Map<String, Object> params, String key) {
        return ContactCodec.strList(params.get(key));
    }

    static int limit(@Nullable Object raw, int def, int max) {
        int value = def;
        if (raw instanceof Number n) value = n.intValue();
        else if (raw instanceof String s && !s.isBlank()) {
            try {
                value = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                value = def;
            }
        }
        return Math.min(Math.max(value, 1), max);
    }

    // ── Results ───────────────────────────────────────────────────

    /** One row for lists: enough to pick the right card, not the whole card. */
    static Map<String, Object> summary(ContactEntry e) {
        ContactCard c = e.card();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("scope", e.scope().wire());
        m.put("kind", c.kind().wire());
        m.put("name", c.displayName());
        if (c.org() != null && c.org().name() != null) m.put("org", c.org().name());
        if (c.title() != null) m.put("title", c.title());
        String email = ContactCard.preferred(c.emails());
        if (email != null) m.put("email", email);
        String phone = ContactCard.preferred(c.phones());
        if (phone != null) m.put("phone", phone);
        if (!c.categories().isEmpty()) m.put("categories", c.categories());
        if (c.kind() == ContactKind.GROUP) m.put("memberCount", c.members().size());
        if (e.photoPath() != null) m.put("hasPhoto", true);
        return m;
    }

    /** The whole card plus where it lives, its members (groups) and the groups listing it. */
    Map<String, Object> full(ContactCaller caller, ContactEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("scope", e.scope().wire());
        Map<String, Object> card = ContactCodec.cardToMap(e.card());
        card.remove("photo");
        m.putAll(card);
        m.put("hasPhoto", e.photoPath() != null);
        if (e.card().kind() == ContactKind.GROUP) {
            List<Map<String, Object>> members = new ArrayList<>();
            contactService.resolveMembers(caller, e).forEach((member, hit) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("member", member);
                if (hit != null) {
                    row.put("name", hit.card().displayName());
                    row.put("scope", hit.scope().wire());
                    String email = ContactCard.preferred(hit.card().emails());
                    if (email != null) row.put("email", email);
                } else if (!member.contains(":")) {
                    row.put("found", false);
                }
                members.add(row);
            });
            m.put("members", members);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (ContactEntry g : contactService.groupsContaining(caller, e.id())) {
            groups.add(Map.of(
                    "id",
                    g.id(),
                    "name",
                    g.card().displayName(),
                    "scope",
                    g.scope().wire()));
        }
        if (!groups.isEmpty()) m.put("groups", groups);
        return m;
    }
}
