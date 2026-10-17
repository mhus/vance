package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactBoardService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Read a contact board with its pins resolved. */
@Component
public class ContactBoardGetTool implements Tool {

    private final ContactToolSupport support;
    private final ContactBoardService boardService;

    public ContactBoardGetTool(ContactToolSupport support, ContactBoardService boardService) {
        this.support = support;
        this.boardService = boardService;
    }

    @Override
    public String name() {
        return "contact_board_get";
    }

    @Override
    public String description() {
        return "Read a contact board (kind contact-board): the pinned contacts in order, each with its "
                + "heading (label), its note and the current card data. A pin marked available: false is "
                + "deleted or not visible to you.";
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
        return "read a contact board — pinned contacts with headings and notes";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.READ_ONLY, "contacts");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of("path", ContactBoardTools.PATH_PARAM, "projectId", Map.of("type", "string")),
                "required",
                List.of("path"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        var caller = support.caller(params, ctx);
        String path = ContactToolSupport.requireStr(params, "path");
        return ContactBoardTools.board(ContactToolSupport.run(() -> boardService.get(caller, path)), null);
    }
}
