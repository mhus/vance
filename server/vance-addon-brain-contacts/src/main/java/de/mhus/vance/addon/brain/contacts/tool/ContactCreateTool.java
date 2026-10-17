package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactCodec;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.ContactPatch;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Add a card to the address book. */
@Component
@Slf4j
public class ContactCreateTool implements Tool {

    private final ContactToolSupport support;

    public ContactCreateTool(ContactToolSupport support) {
        this.support = support;
    }

    @Override
    public String name() {
        return "contact_create";
    }

    @Override
    public String description() {
        return "Create a contact card (person, organisation or group). Default scope is 'user' — the "
                + "caller's private contacts; use 'project' or 'tenant' only when asked to share it. "
                + "Needs a name (fullName, name or org.name). Search first so you do not create a duplicate. "
                + "Returns the new id.";
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
        return "add a person, company or group to the address book";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "contacts");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> p = new LinkedHashMap<>(ContactToolSchemas.cardFields());
        p.put("scope", ContactToolSupport.SCOPE_PARAM);
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ContactCaller caller = support.caller(params, ctx);
        ContactScope scope = ContactToolSupport.scope(params, "scope");
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String key : ContactPatch.EDITABLE) {
            if (params.get(key) != null) fields.put(key, params.get(key));
        }
        ContactCard card = ContactCodec.cardFromMap(fields);
        ContactEntry created = ContactToolSupport.run(() -> support.service().create(caller, card, scope));
        log.info(
                "ContactCreateTool id='{}' scope='{}'",
                created.id(),
                created.scope().wire());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", created.id());
        result.put("scope", created.scope().wire());
        result.put("name", created.card().displayName());
        return result;
    }
}
