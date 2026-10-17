package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Add or remove members of a group. */
@Component
public class ContactGroupMembersTool implements Tool {

    private final ContactToolSupport support;

    public ContactGroupMembersTool(ContactToolSupport support) {
        this.support = support;
    }

    @Override
    public String name() {
        return "contact_group_members";
    }

    @Override
    public String description() {
        return "Add or remove members of a contact group (kind group). Members are contact ids, or URIs "
                + "like mailto:x@y for people without a card. A group may only hold contacts of its own or a "
                + "broader scope: a tenant group only tenant contacts, a project group project and tenant "
                + "contacts. Groups do not nest.";
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
        return "add or remove people in a contact group / distribution list";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "contacts");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> strings = Map.of("type", "array", "items", Map.of("type", "string"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", Map.of("type", "string", "description", "The group's id."));
        p.put("scope", ContactToolSupport.SCOPE_PARAM);
        p.put("add", strings);
        p.put("remove", strings);
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p, "required", List.of("id"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ContactCaller caller = support.caller(params, ctx);
        String id = ContactToolSupport.requireStr(params, "id");
        ContactScope scope = ContactToolSupport.scope(params, "scope");
        List<String> add = ContactToolSupport.strList(params, "add");
        List<String> remove = ContactToolSupport.strList(params, "remove");
        if (add.isEmpty() && remove.isEmpty()) throw new ToolException("Pass 'add' and/or 'remove'");
        ContactEntry group =
                ContactToolSupport.run(() -> support.service().changeMembers(caller, id, scope, add, remove));
        return support.full(caller, group);
    }
}
