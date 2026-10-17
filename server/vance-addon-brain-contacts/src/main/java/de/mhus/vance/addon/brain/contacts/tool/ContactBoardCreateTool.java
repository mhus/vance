package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactBoardService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Create an empty contact board. */
@Component
public class ContactBoardCreateTool implements Tool {

    private final ContactToolSupport support;
    private final ContactBoardService boardService;

    public ContactBoardCreateTool(ContactToolSupport support, ContactBoardService boardService) {
        this.support = support;
        this.boardService = boardService;
    }

    @Override
    public String name() {
        return "contact_board_create";
    }

    @Override
    public String description() {
        return "Create an empty contact board (kind contact-board) at a path in this project — a curated "
                + "list of contacts for one topic, each with a heading and a note. Pin contacts with "
                + "contact_board_pin.";
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
        return "create a board of pinned contacts for a topic or project";
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
                        Map.of("type", "string", "description", "e.g. people/meridian.yaml"),
                        "title",
                        Map.of("type", "string"),
                        "projectId",
                        Map.of("type", "string")),
                "required",
                List.of("path"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        var caller = support.caller(params, ctx);
        String path = ContactToolSupport.requireStr(params, "path");
        String title = ContactToolSupport.str(params, "title");
        return ContactBoardTools.board(ContactToolSupport.run(() -> boardService.create(caller, path, title)), null);
    }
}
