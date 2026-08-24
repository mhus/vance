package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.shared.form.FormFieldYamlParser;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads a view document into a {@link ViewNode} tree.
 *
 * <p>Pure and static: no IO, no Spring. The caller supplies the document text
 * and its path, which appears in every message — an app author debugging a
 * view needs to know which file complained, and a view is one of several
 * documents making up one app.
 *
 * <p><b>What it refuses, and why that is the point.</b> A view document is
 * configuration written by hand or by an agent, so the failure mode to design
 * against is not a crash but a page that renders <em>almost</em> right. Three
 * rejections exist for exactly that reason:
 *
 * <ul>
 *   <li>An unknown widget type is refused rather than skipped. A skipped
 *       widget is an empty space, and an empty space is indistinguishable from
 *       a layout the author got wrong.</li>
 *   <li>A <em>planned</em> widget type ({@code if}, {@code repeat}, …) is
 *       refused with a different message: it will exist, just not yet. Telling
 *       an author "unknown widget: if" would send them looking for a typo.</li>
 *   <li>{@code visibleIf} is refused outright. Evaluating conditions needs the
 *       script sandbox, which this iteration does not have — and carrying an
 *       unevaluated condition to the client would show a field that the
 *       document says to hide. That is the one failure that is invisible to
 *       the reader, so it must be visible to the author.</li>
 * </ul>
 *
 * <p>Handlers are the opposite case and are treated the opposite way: an
 * unresolvable one parses fine and is carried. A handler naming a function the
 * program does not export fails at the click, where the reader sees it —
 * nothing is mis-rendered, so refusing the whole page would be the worse
 * trade.
 */
public final class ViewParser {

    /** Nesting bound. A view is a page with a few sections, not a tree. */
    static final int MAX_DEPTH = 12;

    /** Node bound, so one runaway document cannot exhaust the renderer. */
    static final int MAX_NODES = 500;

    private static final Pattern FUNCTION_NAME =
            Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    private static final String NAVIGATE_PREFIX = "navigate:";
    private static final String RELOAD = "reload";

    /** Field keys that only a setting form reads. See {@code rejectSettingFormKeys}. */
    private static final List<String> SETTING_FORM_ONLY_KEYS =
            List.of("showIf", "writeIf", "bindsTo", "choicesFrom");

    private static final String SETTING_FORM_HINT =
            "A view's fields read the record in the state key named by `from`; conditions"
                    + " will be `visibleIf` once expressions land.";

    private ViewParser() {
    }

    /**
     * @param yamlText the view document body.
     * @param docPath  the document's path, for messages.
     * @throws ToolException when the document cannot be rendered as written.
     */
    public static ViewNode parse(String yamlText, String docPath) {
        Object root = BistromathYaml.load(yamlText);
        if (!(root instanceof Map<?, ?> map)) {
            throw new ToolException("View '" + docPath
                    + "' is not a YAML mapping — a view starts with `type: page`.");
        }
        int[] budget = {MAX_NODES};
        return node(map, docPath, "", 0, budget);
    }

    private static ViewNode node(Map<?, ?> raw, String docPath, String at, int depth,
                                 int[] budget) {
        if (depth > MAX_DEPTH) {
            throw new ToolException(where(docPath, at) + ": nested deeper than "
                    + MAX_DEPTH + " levels.");
        }
        if (--budget[0] < 0) {
            throw new ToolException("View '" + docPath + "' has more than "
                    + MAX_NODES + " widgets.");
        }

        String typeRaw = str(raw.get("type"));
        if (typeRaw == null) {
            throw new ToolException(where(docPath, at)
                    + ": missing `type`. Every widget declares one.");
        }
        WidgetType type = WidgetType.parse(typeRaw);
        if (type == null) {
            if (WidgetType.PLANNED.contains(typeRaw)) {
                throw new ToolException(where(docPath, at) + ": widget `" + typeRaw
                        + "` is part of the schema but is not rendered yet — it arrives"
                        + " with the script sandbox.");
            }
            throw new ToolException(where(docPath, at) + ": unknown widget `" + typeRaw
                    + "`. Known: " + known() + ".");
        }

        if (raw.get("visibleIf") != null) {
            throw new ToolException(where(docPath, at) + ": `visibleIf` is not evaluated"
                    + " yet. Remove it — a condition nobody evaluates would show exactly"
                    + " what the document says to hide, and that is the one failure the"
                    + " reader cannot see.");
        }

        // `title` and `label` are the same field. Both spellings read naturally
        // in different places (a page has a title, a button has a label) and
        // making the author remember which is which buys nothing.
        String label = str(raw.get("title"));
        if (label == null) label = str(raw.get("label"));

        String text = str(raw.get("text"));
        String from = str(raw.get("from"));
        List<String> columns = strings(raw.get("columns"), docPath, at + ".columns");
        Map<String, ViewAction> on = handlers(raw.get("on"), docPath, at);

        List<FormFieldDto> fields = List.of();
        if (type == WidgetType.FORM) {
            rejectSettingFormKeys(raw.get("fields"), docPath, at + ".fields");
            fields = FormFieldYamlParser.parseFields(raw.get("fields"), where(docPath, at));
            if (fields.isEmpty()) {
                throw new ToolException(where(docPath, at)
                        + ": a `form` needs at least one entry under `fields`.");
            }
        } else if (raw.get("fields") != null) {
            throw new ToolException(where(docPath, at) + ": `fields` belongs to a `form`,"
                    + " not to a `" + type.wire() + "`.");
        }

        List<ViewNode> children = children(raw.get("children"), type, docPath, at, depth, budget);

        rejectRemovedKeys(raw, docPath, at);
        requireShape(type, label, text, from, docPath, at);

        return new ViewNode(type.wire(), label, text, from, columns, fields, on, children);
    }

    /**
     * Refuse keys that an earlier shape of this schema used.
     *
     * <p>{@code source:} named a folder or a declared table. Both concepts are
     * gone: a widget reads a state key that the program filled (§3 of the
     * spec). Left unrecognised, such a widget would render empty and the author
     * would look for the mistake in the program — so the document is refused
     * where the obsolete key still sits.
     */
    private static void rejectRemovedKeys(Map<?, ?> raw, String docPath, String at) {
        if (raw.get("source") != null) {
            throw new ToolException(where(docPath, at) + ": `source` no longer exists."
                    + " A widget reads a state key: use `from: <key>` and have the program"
                    + " fill it with `vance.state.set(<key>, …)`.");
        }
    }

    /** Per-widget requirements, in one place so the messages stay uniform. */
    private static void requireShape(WidgetType type, @Nullable String label,
                                     @Nullable String text, @Nullable String from,
                                     String docPath, String at) {
        switch (type) {
            case TABLE -> {
                if (from == null) {
                    throw new ToolException(where(docPath, at) + ": a `table` needs `from`,"
                            + " the state key holding its rows.");
                }
            }
            case FORM -> {
                if (from == null) {
                    throw new ToolException(where(docPath, at) + ": a `form` needs `from`,"
                            + " the state key holding the record it shows.");
                }
            }
            case TEXT, MARKDOWN -> {
                // Either a literal or a state key. A widget with neither shows
                // nothing, which in a generic renderer is indistinguishable
                // from a layout mistake.
                if (text == null && from == null) {
                    throw new ToolException(where(docPath, at) + ": a `" + type.wire()
                            + "` needs `text` (a literal) or `from` (a state key).");
                }
            }
            case BUTTON -> {
                if (label == null) {
                    throw new ToolException(where(docPath, at)
                            + ": a `button` needs a `label`.");
                }
            }
            default -> {
                // page / toolbar / tabs carry children and need nothing of their own.
            }
        }
    }

    /**
     * Refuse the setting-form-only keys on a view's fields.
     *
     * <p>Checked against the <b>raw</b> YAML, not the parsed
     * {@link FormFieldDto}, and that is the whole point.
     * {@link FormFieldYamlParser} deliberately does not read {@code bindsTo},
     * {@code showIf}, {@code writeIf} or {@code choicesFrom} — those are
     * setting-form extensions that {@code SettingFormLoader} reads. So a view
     * field carrying one of them arrives with the key silently dropped: no
     * error, no effect, and a conditional field that always shows.
     *
     * <p>Looking at the DTO would therefore have checked a field that is
     * always null. Looking at the document catches it regardless of what the
     * shared parser does with the key, now or later.
     *
     * <p>Recursive, because a {@code repeat} field nests its own fields under
     * {@code item}.
     */
    private static void rejectSettingFormKeys(@Nullable Object rawFields, String docPath,
                                              String at) {
        if (!(rawFields instanceof List<?> list)) return;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> field)) continue;
            String fieldAt = at + "[" + i + "]";
            String name = str(field.get("name"));
            for (String key : SETTING_FORM_ONLY_KEYS) {
                if (field.get(key) == null) continue;
                throw new ToolException(where(docPath, fieldAt) + ": field `"
                        + (name == null ? "?" : name) + "` carries `" + key
                        + "`, which belongs to setting forms and is not read here — it would"
                        + " be dropped without a word. " + SETTING_FORM_HINT);
            }
            rejectSettingFormKeys(field.get("item"), docPath, fieldAt + ".item");
        }
    }

    private static List<ViewNode> children(@Nullable Object raw, WidgetType type,
                                           String docPath, String at, int depth,
                                           int[] budget) {
        if (raw == null) return List.of();
        if (!type.allowsChildren()) {
            throw new ToolException(where(docPath, at) + ": a `" + type.wire()
                    + "` carries no `children`.");
        }
        if (!(raw instanceof List<?> list)) {
            throw new ToolException(where(docPath, at + ".children") + ": expected a list.");
        }
        List<ViewNode> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object child = list.get(i);
            String childAt = at + ".children[" + i + "]";
            if (!(child instanceof Map<?, ?> m)) {
                throw new ToolException(where(docPath, childAt)
                        + ": expected a widget mapping.");
            }
            out.add(node(m, docPath, childAt, depth + 1, budget));
        }
        return List.copyOf(out);
    }

    private static Map<String, ViewAction> handlers(@Nullable Object raw, String docPath,
                                                   String at) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ToolException(where(docPath, at + ".on")
                    + ": expected a mapping of event name to handler.");
        }
        Map<String, ViewAction> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String event = str(e.getKey());
            if (event == null) continue;
            String handler = str(e.getValue());
            if (handler == null) {
                throw new ToolException(where(docPath, at + ".on." + event)
                        + ": handler is empty.");
            }
            out.put(event, action(handler, docPath, at + ".on." + event));
        }
        return Map.copyOf(out);
    }

    /**
     * Parse one handler string.
     *
     * <p>The {@code navigate:} prefix is tested before the generic
     * {@code ref:function} split, because that split takes the last colon —
     * {@code navigate:edit} would otherwise parse as a script named
     * "navigate" exporting "edit".
     */
    static ViewAction action(String handler, String docPath, String at) {
        String s = handler.trim();
        if (RELOAD.equals(s)) return ViewAction.reload(s);

        if (s.startsWith(NAVIGATE_PREFIX)) {
            String handle = s.substring(NAVIGATE_PREFIX.length()).trim();
            if (handle.isEmpty()) {
                throw new ToolException(where(docPath, at)
                        + ": `navigate:` needs a view handle.");
            }
            return ViewAction.navigate(handle, s);
        }

        int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            throw new ToolException(where(docPath, at) + ": cannot read handler `" + s
                    + "`. Write `navigate:<handle>`, `reload`, or"
                    + " `<script-document>:<function>`.");
        }
        String ref = s.substring(0, colon).trim();
        String function = s.substring(colon + 1).trim();
        if (ref.isEmpty()) {
            throw new ToolException(where(docPath, at) + ": handler `" + s
                    + "` names no script document.");
        }
        if (!FUNCTION_NAME.matcher(function).matches()) {
            throw new ToolException(where(docPath, at) + ": `" + function
                    + "` is not a function name.");
        }
        return ViewAction.script(ref, function, s);
    }

    private static List<String> strings(@Nullable Object raw, String docPath, String at) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new ToolException(where(docPath, at) + ": expected a list of names.");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            String s = str(o);
            if (s != null) out.add(s);
        }
        return List.copyOf(out);
    }

    private static @Nullable String str(@Nullable Object v) {
        if (v == null) return null;
        String s = v instanceof String str ? str : String.valueOf(v);
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static String known() {
        List<String> names = new ArrayList<>();
        for (WidgetType t : WidgetType.values()) names.add(t.wire());
        return String.join(", ", names);
    }

    private static String where(String docPath, String at) {
        return at.isEmpty() ? docPath : docPath + " " + at;
    }
}
