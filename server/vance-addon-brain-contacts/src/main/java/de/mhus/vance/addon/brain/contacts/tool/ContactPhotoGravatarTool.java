package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Fetch a contact's public Gravatar avatar. */
@Component
public class ContactPhotoGravatarTool implements Tool {

    private final ContactToolSupport support;

    public ContactPhotoGravatarTool(ContactToolSupport support) {
        this.support = support;
    }

    @Override
    public String name() {
        return "contact_photo_gravatar";
    }

    @Override
    public String description() {
        return "Look up the public Gravatar avatar for a contact's e-mail (the preferred one, or 'email') "
                + "and keep it as the contact's picture. Asks gravatar.com — use it when the user wants a "
                + "picture, not on every new contact. Answers found: false when there is none.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "get a contact's avatar picture from Gravatar by e-mail";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, ToolLabels.SIDE_EFFECT, "contacts");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", Map.of("type", "string"));
        p.put("scope", ContactToolSupport.SCOPE_PARAM);
        p.put("email", Map.of("type", "string", "description", "Which address to look up; default the preferred one."));
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p, "required", List.of("id"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ContactCaller caller = support.caller(params, ctx);
        String id = ContactToolSupport.requireStr(params, "id");
        ContactScope scope = ContactToolSupport.scope(params, "scope");
        String email = ContactToolSupport.str(params, "email");
        Optional<ContactEntry> updated =
                ContactToolSupport.run(() -> support.service().setPhotoFromGravatar(caller, id, scope, email));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("found", updated.isPresent());
        return result;
    }
}
