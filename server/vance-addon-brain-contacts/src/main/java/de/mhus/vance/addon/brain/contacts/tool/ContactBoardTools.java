package de.mhus.vance.addon.brain.contacts.tool;

import de.mhus.vance.addon.brain.contacts.ContactBoardService;
import de.mhus.vance.addon.brain.contacts.ContactEntry;
import de.mhus.vance.addon.brain.contacts.model.ContactCard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Result shaping shared by the {@code contact_board_*} tools. */
final class ContactBoardTools {

    private ContactBoardTools() {}

    static final Map<String, Object> PATH_PARAM =
            Map.of("type", "string", "description", "Path of the contact-board document in this project.");

    static Map<String, Object> board(ContactBoardService.ResolvedBoard b, @Nullable String warning) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", b.path());
        if (b.board().title() != null) m.put("title", b.board().title());
        List<Map<String, Object>> pins = new ArrayList<>();
        for (ContactBoardService.ResolvedPin p : b.pins()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.pin().uuid());
            if (p.pin().label() != null) row.put("label", p.pin().label());
            ContactEntry e = p.entry();
            if (e != null) {
                ContactCard c = e.card();
                row.put("name", c.displayName());
                row.put("scope", e.scope().wire());
                if (c.org() != null && c.org().name() != null)
                    row.put("org", c.org().name());
                String email = ContactCard.preferred(c.emails());
                if (email != null) row.put("email", email);
                String phone = ContactCard.preferred(c.phones());
                if (phone != null) row.put("phone", phone);
            } else {
                row.put("available", false);
            }
            if (p.pin().note() != null) row.put("note", p.pin().note());
            pins.add(row);
        }
        m.put("pins", pins);
        if (warning != null) m.put("warning", warning);
        return m;
    }

    static @Nullable Integer position(Map<String, Object> params) {
        Object raw = params.get("position");
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Like {@code str} but keeps "" — it means "clear" for label and note. */
    static @Nullable String raw(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof String s ? s : null;
    }
}
