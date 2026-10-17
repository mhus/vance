package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.ContactPatch;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Change fields of a card. */
@Component
@Slf4j
public class ContactUpdateTool implements Tool {

    private final ContactToolSupport support;

    public ContactUpdateTool(ContactToolSupport support) {
        this.support = support;
    }

    @Override
    public String name() {
        return "contact_update";
    }

    @Override
    public String description() {
        return "Change a contact. Only the fields you pass change; a passed field replaces the stored value "
                + "and \"\"/null/[] clears it. Lists are replaced whole — to add or drop single entries use "
                + "addEmails/removeEmails, addPhones/removePhones, addCategories/removeCategories. Pass the "
                + "scope the card lives in (from contact_search); default is 'user'.";
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
        return "change a contact's phone, e-mail, address, organisation or notes";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "contacts");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> strings = Map.of("type", "array", "items", Map.of("type", "string"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", Map.of("type", "string", "description", "The contact id."));
        p.put("scope", ContactToolSupport.SCOPE_PARAM);
        p.putAll(ContactToolSchemas.cardFields());
        p.put(
                "addEmails",
                Map.of(
                        "type",
                        "array",
                        "description",
                        "E-mails to add ({value, types?} or string).",
                        "items",
                        Map.of("type", "object")));
        p.put("removeEmails", strings);
        p.put(
                "addPhones",
                Map.of(
                        "type",
                        "array",
                        "description",
                        "Phones to add ({value, types?} or string).",
                        "items",
                        Map.of("type", "object")));
        p.put(
                "removePhones",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "Numbers to drop, any notation."));
        p.put("addCategories", strings);
        p.put("removeCategories", strings);
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p, "required", List.of("id"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ContactCaller caller = support.caller(params, ctx);
        String id = ContactToolSupport.requireStr(params, "id");
        ContactScope scope = ContactToolSupport.scope(params, "scope");
        if (ContactPatch.isEmpty(params)) throw new ToolException("Nothing to change — pass at least one field");
        ContactEntry updated = ContactToolSupport.run(
                () -> support.service().update(caller, id, scope, card -> ContactPatch.apply(card, params)));
        log.info(
                "ContactUpdateTool id='{}' scope='{}'",
                updated.id(),
                updated.scope().wire());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", true);
        result.putAll(support.full(caller, updated));
        return result;
    }
}
