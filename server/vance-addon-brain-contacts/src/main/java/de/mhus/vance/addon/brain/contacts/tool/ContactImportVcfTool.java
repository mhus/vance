package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactCaller;
import de.mhus.vance.addon.brain.contacts.ContactVCardService;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Import a .vcf document into the address book. */
@Component
public class ContactImportVcfTool implements Tool {

    private final ContactToolSupport support;
    private final ContactVCardService vcardService;
    private final DocumentService documentService;

    public ContactImportVcfTool(
            ContactToolSupport support, ContactVCardService vcardService, DocumentService documentService) {
        this.support = support;
        this.vcardService = vcardService;
        this.documentService = documentService;
    }

    @Override
    public String name() {
        return "contact_import_vcf";
    }

    @Override
    public String description() {
        return "Import vCards (.vcf — Google, Apple, Outlook exports; vCard 2.1/3.0/4.0) from a document of "
                + "this project into the address book. Embedded pictures are taken along. Importing the same "
                + "file again updates instead of duplicating. Default scope 'user'.";
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
        return "import a vCard / .vcf address book file";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "contacts");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", Map.of("type", "string", "description", "Path of the .vcf document in this project."));
        p.put("scope", ContactToolSupport.SCOPE_PARAM);
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p, "required", java.util.List.of("path"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = ContactToolSupport.requireStr(params, "path");
        ContactScope scope = ContactToolSupport.scope(params, "scope");
        ProjectDocument project = support.project(params, ctx);
        DocumentDocument doc = documentService
                .findByPath(ctx.tenantId(), project.getName(), path)
                .orElseThrow(() -> new ToolException("No document '" + path + "' in " + project.getName()));
        String vcf = documentService.readContent(doc);
        ContactCaller caller = support.caller(params, ctx);
        ContactVCardService.ImportReport report =
                ContactToolSupport.run(() -> vcardService.importVcf(caller, vcf, scope));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", report.scope().wire());
        result.put("created", report.created());
        result.put("updated", report.updated());
        if (!report.skipped().isEmpty()) {
            result.put(
                    "skipped",
                    report.skipped().stream()
                            .map(s -> s.name() + ": " + s.reason())
                            .toList());
        }
        if (!report.warnings().isEmpty()) result.put("warnings", report.warnings());
        return result;
    }
}
