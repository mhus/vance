package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.ContactService;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Move a card between scopes. */
@Component
public class ContactMoveTool implements Tool {

    private final ContactToolSupport support;

    public ContactMoveTool(ContactToolSupport support) {
        this.support = support;
    }

    @Override
    public String name() {
        return "contact_move";
    }

    @Override
    public String description() {
        return "Move a contact to another scope, keeping its id and picture — e.g. share a private contact "
                + "with the project (to: project) or the whole tenant (to: tenant).";
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
        return "share a private contact with the project or tenant, or take it private";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "contacts");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", Map.of("type", "string"));
        p.put("from", ContactToolSupport.SCOPE_PARAM);
        p.put("to", ContactToolSupport.SCOPE_PARAM);
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p, "required", List.of("id", "to"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ContactCaller caller = support.caller(params, ctx);
        String id = ContactToolSupport.requireStr(params, "id");
        ContactScope from = ContactToolSupport.scope(params, "from");
        ContactScope to = ContactToolSupport.scope(params, "to");
        if (to == null) throw new ToolException("to is required");
        ContactService.MoveResult moved =
                ContactToolSupport.run(() -> support.service().move(caller, id, from, to));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", moved.entry().id());
        result.put("scope", moved.entry().scope().wire());
        if (!moved.strandedGroups().isEmpty()) {
            List<Map<String, Object>> groups = new ArrayList<>();
            for (ContactEntry g : moved.strandedGroups()) groups.add(ContactToolSupport.summary(g));
            result.put("groupsThatLostIt", groups);
            result.put("hint", "These groups are broader than the new scope and can no longer show the contact.");
        }
        return result;
    }
}
