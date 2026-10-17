package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.ContactService;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Delete a card (to the project trash). */
@Component
@Slf4j
public class ContactDeleteTool implements Tool {

    private final ContactToolSupport support;

    public ContactDeleteTool(ContactToolSupport support) {
        this.support = support;
    }

    @Override
    public String name() {
        return "contact_delete";
    }

    @Override
    public String description() {
        return "Delete a contact and its picture (they go to the trash of the scope's project). Pass the "
                + "scope it lives in; default is 'user'. Groups that listed it keep the id and show it as "
                + "not found — the result names them.";
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
        return "remove a contact from the address book";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "contacts");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", Map.of("type", "string"));
        p.put("scope", ContactToolSupport.SCOPE_PARAM);
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p, "required", List.of("id"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ContactCaller caller = support.caller(params, ctx);
        String id = ContactToolSupport.requireStr(params, "id");
        ContactScope scope = ContactToolSupport.scope(params, "scope");
        ContactService.GroupImpact impact =
                ContactToolSupport.run(() -> support.service().delete(caller, id, scope));
        log.info("ContactDeleteTool id='{}'", id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", id);
        if (!impact.groups().isEmpty()) {
            List<Map<String, Object>> groups = new ArrayList<>();
            for (ContactEntry g : impact.groups()) groups.add(ContactToolSupport.summary(g));
            result.put("stillListedIn", groups);
        }
        return result;
    }
}
