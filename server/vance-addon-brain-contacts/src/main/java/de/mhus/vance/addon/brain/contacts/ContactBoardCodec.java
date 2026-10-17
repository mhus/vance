package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.addon.brain.contacts.model.ContactBoard;
import de.mhus.vance.addon.brain.contacts.model.ContactPin;
import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.KindHeaderCodec;
import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parser and serialiser for {@code kind: contact-board}:
 * <pre>
 * $meta:
 *   kind: contact-board
 * title: Vertrag Meridian
 * pins:
 *   - uuid: 3f0c…
 *     scope: tenant
 *     label: Ansprechpartnerin Einkauf
 *     note: |
 *       Entscheidet über Rahmenverträge.
 * </pre>
 * Lenient on read — a pin without a readable UUID is dropped, a second pin
 * of the same contact is dropped — and {@link #validate} names both.
 */
public final class ContactBoardCodec {

    public static final String KIND = "contact-board";

    private static final Set<String> TOP_FIELDS = Set.of("kind", "title", "description", "pins");
    private static final Set<String> PIN_FIELDS = Set.of("uuid", "scope", "label", "note");

    private ContactBoardCodec() {}

    public static ContactBoard parse(String body) {
        Map<String, Object> top = KindHeaderCodec.parseYamlBody(body);
        List<ContactPin> pins = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Object raw = top.get("pins");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> map)) continue;
                Map<String, Object> m = ContactCodec.stringKeys(map);
                String uuid = ContactIds.normalize(ContactCodec.str(m.get("uuid")));
                if (uuid == null || !seen.add(uuid)) continue;
                pins.add(new ContactPin(
                        uuid,
                        ContactCodec.scopeOrNull(ContactCodec.str(m.get("scope"))),
                        ContactCodec.str(m.get("label")),
                        ContactCodec.str(m.get("note"))));
            }
        } else if (raw != null) {
            throw new KindCodecException("contact-board 'pins' must be a list");
        }
        return new ContactBoard(ContactCodec.str(top.get("title")), ContactCodec.str(top.get("description")), pins);
    }

    public static String serialize(ContactBoard board) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (board.title() != null) body.put("title", board.title());
        if (board.description() != null) body.put("description", board.description());
        List<Map<String, Object>> pins = new ArrayList<>();
        for (ContactPin p : board.pins()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", p.uuid());
            if (p.scope() != null) m.put("scope", p.scope().wire());
            if (p.label() != null) m.put("label", p.label());
            if (p.note() != null) m.put("note", p.note());
            pins.add(m);
        }
        body.put("pins", pins);
        return KindHeaderCodec.dumpYamlBody(KIND, body);
    }

    public static List<Finding> validate(String body, String location) {
        List<Finding> out = new ArrayList<>();
        Map<String, Object> top;
        try {
            top = KindHeaderCodec.parseYamlBody(body);
        } catch (KindCodecException e) {
            out.add(Finding.error(location, "contact-board-parse", "Parse error: " + e.getMessage()));
            return out;
        }
        for (String key : top.keySet()) {
            if (!TOP_FIELDS.contains(key)) {
                out.add(Finding.warning(
                        location + ":" + key, "contact-board-unknown-field", "Unknown field '" + key + "' is ignored"));
            }
        }
        Object raw = top.get("pins");
        if (raw == null) return out;
        if (!(raw instanceof List<?> list)) {
            out.add(Finding.error(location + ":pins", "contact-board-shape", "'pins' must be a list"));
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String at = location + ":pins[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                out.add(Finding.error(at, "contact-board-shape", "A pin must be a mapping with a uuid"));
                continue;
            }
            Map<String, Object> m = ContactCodec.stringKeys(map);
            for (String key : m.keySet()) {
                if (!PIN_FIELDS.contains(key)) {
                    out.add(Finding.warning(
                            at + "." + key, "contact-board-unknown-field", "Unknown field '" + key + "' is ignored"));
                }
            }
            String uuid = ContactIds.normalize(ContactCodec.str(m.get("uuid")));
            if (uuid == null) {
                out.add(Finding.error(
                        at + ".uuid", "contact-board-uuid", "Missing or not a UUID — the pin is dropped"));
            } else if (!seen.add(uuid)) {
                out.add(Finding.error(
                        at + ".uuid", "contact-board-duplicate", "Contact pinned twice — the second pin is dropped"));
            }
            String scope = ContactCodec.str(m.get("scope"));
            if (scope != null && ContactCodec.scopeOrNull(scope) == null) {
                out.add(Finding.error(
                        at + ".scope",
                        "contact-board-scope",
                        "Unknown scope '" + scope + "' — user, project or tenant"));
            }
        }
        return out;
    }
}
