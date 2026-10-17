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

/**
 * Set or remove a contact's picture from an image document of the project.
 *
 * <p>No URL parameter, on purpose: asked for a picture, a model invents a
 * plausible URL. The picture comes from a document someone put there, or
 * from Gravatar through its own tool.
 */
@Component
public class ContactPhotoSetTool implements Tool {

    private final ContactToolSupport support;

    public ContactPhotoSetTool(ContactToolSupport support) {
        this.support = support;
    }

    @Override
    public String name() {
        return "contact_photo_set";
    }

    @Override
    public String description() {
        return "Set a contact's picture from an image document of this project (photoPath), or remove it "
                + "(remove: true). The picture is cropped to a square and scaled down. There is no URL "
                + "parameter — never guess a picture address; for a public avatar use contact_photo_gravatar.";
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
        return "set or remove a contact's photo from an image in the project";
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
        p.put("photoPath", Map.of("type", "string", "description", "Path of a JPEG/PNG/GIF document in this project."));
        p.put("remove", Map.of("type", "boolean", "description", "true removes the picture."));
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p, "required", List.of("id"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ContactCaller caller = support.caller(params, ctx);
        String id = ContactToolSupport.requireStr(params, "id");
        ContactScope scope = ContactToolSupport.scope(params, "scope");
        String path = ContactToolSupport.str(params, "photoPath");
        boolean remove = Boolean.TRUE.equals(params.get("remove")) || "true".equals(params.get("remove"));
        if (remove == (path != null)) throw new ToolException("Pass either photoPath or remove: true");
        ContactEntry entry = ContactToolSupport.run(() -> remove
                ? support.service().removePhoto(caller, id, scope)
                : support.service().setPhotoFromDocument(caller, id, scope, path));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entry.id());
        result.put("hasPhoto", entry.photoPath() != null);
        return result;
    }
}
