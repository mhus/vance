package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.ContactSearch;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Search the address book — the way into every other contact tool. */
@Component
public class ContactSearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 200;

    private final ContactToolSupport support;

    public ContactSearchTool(ContactToolSupport support) {
        this.support = support;
    }

    @Override
    public String name() {
        return "contact_search";
    }

    @Override
    public String description() {
        return "Search the address book (people, organisations, groups) of the user, the project and the "
                + "tenant. Free-text 'query' matches names, e-mails, phone numbers, organisation, categories "
                + "and notes; the other filters narrow further. Without any filter it lists everything. "
                + "Each hit carries its id and scope — pass both to contact_get/contact_update.";
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
        p.put("query", Map.of("type", "string", "description", "Free text; every word must match somewhere."));
        p.put("email", Map.of("type", "string", "description", "Part of an e-mail address."));
        p.put("phone", Map.of("type", "string", "description", "A phone number in any notation."));
        p.put("org", Map.of("type", "string", "description", "Part of the organisation name."));
        p.put("category", Map.of("type", "string", "description", "Exact category label."));
        p.put("kind", Map.of("type", "string", "enum", List.of("individual", "org", "group")));
        p.put("scope", ContactToolSupport.SCOPE_PARAM);
        p.put("limit", Map.of("type", "integer", "description", "Maximum hits. Default 25."));
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ContactCaller caller = support.caller(params, ctx);
        ContactScope scope = ContactToolSupport.scope(params, "scope");
        ContactSearch.Criteria criteria = new ContactSearch.Criteria(
                ContactToolSupport.str(params, "query"),
                ContactToolSupport.str(params, "email"),
                ContactToolSupport.str(params, "phone"),
                ContactToolSupport.str(params, "org"),
                ContactToolSupport.str(params, "category"),
                ContactToolSupport.kind(params));
        int limit = ContactToolSupport.limit(params.get("limit"), DEFAULT_LIMIT, MAX_LIMIT);
        List<ContactEntry> hits = ContactToolSupport.run(() -> support.service().search(caller, scope, criteria));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ContactEntry e : hits) {
            if (rows.size() >= limit) break;
            rows.add(ContactToolSupport.summary(e));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", hits.size());
        result.put("contacts", rows);
        if (hits.size() > rows.size()) {
            result.put(
                    "truncated",
                    "Showing " + rows.size() + " of " + hits.size() + " — narrow the query or raise 'limit'.");
        }
        List<String> broken = new ArrayList<>();
        support.service().broken(caller).forEach((s, paths) -> paths.forEach(p -> broken.add(s.wire() + ": " + p)));
        if (!broken.isEmpty()) {
            result.put("unreadable", broken);
            result.put(
                    "unreadableHint",
                    "These card files did not parse and are missing from the result — " + "run kind_validate on them.");
        }
        return result;
    }
}
