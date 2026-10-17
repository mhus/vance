package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactBoardService;
import de.mhus.vance.addon.brain.contacts.model.ContactScope;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Pin a contact to a board. */
@Component
public class ContactBoardPinTool implements Tool {

    private final ContactToolSupport support;
    private final ContactBoardService boardService;

    public ContactBoardPinTool(ContactToolSupport support, ContactBoardService boardService) {
        this.support = support;
        this.boardService = boardService;
    }

    @Override
    public String name() {
        return "contact_board_pin";
    }

    @Override
    public String description() {
        return "Pin an address-book contact (id from contact_search) to a contact board, optionally with a "
                + "heading (label, e.g. 'Ansprechpartnerin Einkauf'), a Markdown note and a 0-based position. "
                + "The board references the contact, so it always shows the current card. Answers a warning "
                + "when other readers of the board cannot see the contact.";
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
        return "pin a contact to a contact board with a heading and note";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.WRITE, "contacts", "document");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", ContactBoardTools.PATH_PARAM);
        p.put("id", Map.of("type", "string", "description", "The contact id."));
        p.put("scope", ContactToolSupport.SCOPE_PARAM);
        p.put("label", Map.of("type", "string", "description", "Heading above the card."));
        p.put("note", Map.of("type", "string", "description", "Markdown note below the card."));
        p.put("position", Map.of("type", "integer", "description", "0-based; default at the end."));
        p.put("projectId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", p, "required", List.of("path", "id"));
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        var caller = support.caller(params, ctx);
        String path = ContactToolSupport.requireStr(params, "path");
        String id = ContactToolSupport.requireStr(params, "id");
        ContactScope scope = ContactToolSupport.scope(params, "scope");
        ContactBoardService.PinResult result = ContactToolSupport.run(() -> boardService.pin(
                caller,
                path,
                id,
                scope,
                ContactToolSupport.str(params, "label"),
                ContactToolSupport.str(params, "note"),
                ContactBoardTools.position(params)));
        return ContactBoardTools.board(result.board(), result.warning());
    }
}
