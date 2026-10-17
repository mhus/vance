package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactBoardService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Remove a pin from a board (the contact itself stays). */
@Component
public class ContactBoardUnpinTool implements Tool {

    private final ContactToolSupport support;
    private final ContactBoardService boardService;

    public ContactBoardUnpinTool(ContactToolSupport support, ContactBoardService boardService) {
        this.support = support;
        this.boardService = boardService;
    }

    @Override
    public String name() {
        return "contact_board_unpin";
    }

    @Override
    public String description() {
        return "Remove a contact from a contact board — with its heading and note. The contact itself stays "
                + "in the address book.";
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
        return "remove a pinned contact from a contact board";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "contacts", "document");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "path",
                        ContactBoardTools.PATH_PARAM,
                        "id",
                        Map.of("type", "string"),
                        "projectId",
                        Map.of("type", "string")),
                "required",
                List.of("path", "id"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        var caller = support.caller(params, ctx);
        String path = ContactToolSupport.requireStr(params, "path");
        String id = ContactToolSupport.requireStr(params, "id");
        return ContactBoardTools.board(ContactToolSupport.run(() -> boardService.unpin(caller, path, id)), null);
    }
}
