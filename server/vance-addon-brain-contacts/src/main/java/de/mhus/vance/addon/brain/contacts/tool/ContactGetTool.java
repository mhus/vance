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
import java.util.Set;
import org.springframework.stereotype.Component;

/** Read one whole card. */
@Component
public class ContactGetTool implements Tool {

    private final ContactToolSupport support;

    public ContactGetTool(ContactToolSupport support) {
        this.support = support;
    }

    @Override
    public String name() {
        return "contact_get";
    }

    @Override
    public String description() {
        return "Read one contact card in full: all e-mails, phones, addresses, organisation, notes; for a "
                + "group its members, and the groups the contact belongs to. Without 'scope' the most "
                + "specific scope holding the id wins (project, then user, then tenant).";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.READ_ONLY, "contacts");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", Map.of("type", "string", "description", "The contact id (from contact_search)."));
        p.put("scope", ContactToolSupport.SCOPE_PARAM);
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p, "required", List.of("id"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ContactCaller caller = support.caller(params, ctx);
        String id = ContactToolSupport.requireStr(params, "id");
        ContactScope scope = ContactToolSupport.scope(params, "scope");
        ContactEntry entry = ContactToolSupport.run(() -> support.service().get(caller, id, scope));
        return support.full(caller, entry);
    }
}
