package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactBoardService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Change a pin's heading, note or position. */
@Component
public class ContactBoardUpdateTool implements Tool {

    private final ContactToolSupport support;
    private final ContactBoardService boardService;

    public ContactBoardUpdateTool(ContactToolSupport support, ContactBoardService boardService) {
        this.support = support;
        this.boardService = boardService;
    }

    @Override
    public String name() {
        return "contact_board_update";
    }

    @Override
    public String description() {
        return "Change a pin on a contact board: heading (label), Markdown note, or position (0-based). "
                + "Omitted = unchanged, \"\" = clear. The note replaces the stored one — read it first with "
                + "contact_board_get when adding to it.";
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
        return "edit the note or heading of a pinned contact, reorder a board";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "contacts", "document");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", ContactBoardTools.PATH_PARAM);
        p.put("id", Map.of("type", "string", "description", "The pinned contact's id."));
        p.put("label", Map.of("type", "string"));
        p.put("note", Map.of("type", "string", "description", "Markdown."));
        p.put("position", Map.of("type", "integer"));
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p, "required", List.of("path", "id"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        var caller = support.caller(params, ctx);
        String path = ContactToolSupport.requireStr(params, "path");
        String id = ContactToolSupport.requireStr(params, "id");
        String label = ContactBoardTools.raw(params, "label");
        String note = ContactBoardTools.raw(params, "note");
        Integer position = ContactBoardTools.position(params);
        if (label == null && note == null && position == null) {
            throw new ToolException("Pass label, note and/or position");
        }
        return ContactBoardTools.board(
                ContactToolSupport.run(() -> boardService.updatePin(caller, path, id, label, note, position)), null);
    }
}
