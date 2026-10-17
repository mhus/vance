package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.ContactSearch;
import de.mhus.vance.addon.brain.contacts.ContactVCardService;
import de.mhus.vance.addon.brain.contacts.model.ContactKind;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.project.ProjectDocument;
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

/** Export contacts as a vCard 4.0 document. */
@Component
public class ContactExportVcfTool implements Tool {

    private final ContactToolSupport support;
    private final ContactVCardService vcardService;
    private final DocumentService documentService;

    public ContactExportVcfTool(
            ContactToolSupport support, ContactVCardService vcardService, DocumentService documentService) {
        this.support = support;
        this.vcardService = vcardService;
        this.documentService = documentService;
    }

    @Override
    public String name() {
        return "contact_export_vcf";
    }

    @Override
    public String description() {
        return "Write contacts as a vCard 4.0 file (.vcf) into a document of this project — pictures embedded. "
                + "Select by 'ids', by 'group' (its members), by 'category', or a whole 'scope'.";
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
        return "export contacts as a vCard / .vcf file";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "contacts", "document");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", Map.of("type", "string", "description", "Target document, e.g. exports/contacts.vcf."));
        p.put("ids", Map.of("type", "array", "items", Map.of("type", "string")));
        p.put("group", Map.of("type", "string", "description", "Id of a group — exports its members."));
        p.put("category", Map.of("type", "string"));
        p.put("scope", ContactToolSupport.SCOPE_PARAM);
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p, "required", List.of("path"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = ContactToolSupport.requireStr(params, "path");
        ContactCaller caller = support.caller(params, ctx);
        ContactScope scope = ContactToolSupport.scope(params, "scope");
        List<String> ids = ContactToolSupport.strList(params, "ids");
        String group = ContactToolSupport.str(params, "group");
        List<ContactEntry> entries = ContactToolSupport.run(
                () -> select(caller, scope, ids, group, ContactToolSupport.str(params, "category")));
        if (entries.isEmpty()) throw new ToolException("Nothing selected — no contact matches");
        String vcf = ContactToolSupport.run(() -> vcardService.exportVcf(caller, entries));
        ProjectDocument project = support.project(params, ctx);
        // The target path comes from the caller: a plain user write, checked like any doc_write.
        documentService.upsertText(
                ctx.tenantId(),
                project.getName(),
                path,
                null,
                null,
                vcf,
                ctx.userId(),
                WriteActor.user(support.subject(ctx)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("count", entries.size());
        return result;
    }

    private List<ContactEntry> select(
            ContactCaller caller, ContactScope scope, List<String> ids, String group, String category) {
        if (!ids.isEmpty()) {
            List<ContactEntry> out = new ArrayList<>();
            for (String id : ids) out.add(support.service().get(caller, id, scope));
            return out;
        }
        if (group != null) {
            ContactEntry g = support.service().get(caller, group, scope);
            if (g.card().kind() != ContactKind.GROUP)
                throw new IllegalArgumentException("'" + group + "' is not a group");
            List<ContactEntry> out = new ArrayList<>();
            support.service().resolveMembers(caller, g).values().forEach(e -> {
                if (e != null) out.add(e);
            });
            return out;
        }
        return support.service()
                .search(caller, scope, new ContactSearch.Criteria(null, null, null, null, category, null));
    }
}
