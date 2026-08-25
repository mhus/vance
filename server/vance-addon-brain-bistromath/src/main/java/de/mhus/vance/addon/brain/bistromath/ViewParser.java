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
 *   <li>A <em>planned</em> widget type ({@code chart}) is refused with a
 *       different message: it will exist, just not yet. Telling an author
 *       "unknown widget: chart" would send them looking for a typo.</li>
 *   <li>{@code visibleIf} is refused and its message names the replacement.
 *       A condition is a <em>state key</em> here ({@code show:}), never an
 *       expression — the program computes the boolean, the widget reads it.
 *       Carrying an unevaluated condition to the client would show exactly what
 *       the document says to hide, which is the one failure the reader cannot
 *       see; so it must be visible to the author.</li>
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

    /** The drawing surface takes whatever space is left. */
    static final String REGION_FILL = "fill";

    /** Upper bound on a declared surface height, so a typo cannot make a page endless. */
    static final int MAX_REGION_PX = 4000;

    /** A widget id: what a program writes in a patch call. */
    private static final Pattern WIDGET_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*$");

    private static final String NAVIGATE_PREFIX = "navigate:";
    private static final String RELOAD = "reload";

    /**
     * Severities an {@code alert} and colours a {@code badge} may carry.
     *
     * <p>Closed, because these are meanings and not shades: "warning" says
     * something about the message. An open string would let a document ask for
     * a colour, and a colour in an app document is the line §1.3 draws.
     */
    static final List<String> VARIANTS =
            List.of("neutral", "info", "success", "warning", "error");

    /**
     * Author's word → mime type, for a {@code code} widget.
     *
     * <p>Resolved here rather than in the client so the list exists once and a
     * typo is a parse error instead of a silent fall-through to plain text —
     * which is exactly the "renders almost right" failure this parser is for.
     */
    private static final Map<String, String> CODE_LANGUAGES = Map.ofEntries(
            Map.entry("markdown", "text/markdown"),
            Map.entry("md", "text/markdown"),
            Map.entry("json", "application/json"),
            Map.entry("yaml", "application/yaml"),
            Map.entry("yml", "application/yaml"),
            Map.entry("javascript", "text/javascript"),
            Map.entry("js", "text/javascript"),
            Map.entry("typescript", "application/typescript"),
            Map.entry("ts", "application/typescript"),
            Map.entry("python", "text/x-python"),
            Map.entry("py", "text/x-python"),
            Map.entry("shell", "application/x-sh"),
            Map.entry("sh", "application/x-sh"),
            Map.entry("bash", "application/x-sh"),
            Map.entry("r", "text/x-r"),
            Map.entry("html", "text/html"),
            Map.entry("css", "text/css"),
            Map.entry("xml", "application/xml"),
            Map.entry("sql", "application/sql"),
            Map.entry("java", "text/x-java"),
            Map.entry("text", "text/plain"));

    /** Field keys that only a setting form reads. See {@code rejectSettingFormKeys}. */
    private static final List<String> SETTING_FORM_ONLY_KEYS =
            List.of("showIf", "writeIf", "bindsTo", "choicesFrom");

    private static final String SETTING_FORM_HINT =
            "A view's fields read the record in the state key named by `from`; a condition"
                    + " on a whole widget is `show: <state key>`.";

    private ViewParser() {
    }

    /**
     * @param yamlText the view document body.
     * @param docPath  the document's path, for messages.
     * @throws ToolException when the document cannot be rendered as written.
     */
    public static ViewNode parse(String yamlText, String docPath) {
        Object root = BistromathYaml.load(yamlText, docPath);
        if (!(root instanceof Map<?, ?> map)) {
            throw new ToolException("View '" + docPath
                    + "' is not a YAML mapping — a view starts with `type: page`.");
        }
        int[] budget = {MAX_NODES};
        return node(map, docPath, "", 0, budget, new java.util.HashSet<>());
    }

    private static ViewNode node(Map<?, ?> raw, String docPath, String at, int depth,
                                 int[] budget, java.util.Set<String> budgetIds) {
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
            throw new ToolException(where(docPath, at) + ": `visibleIf` is not a thing."
                    + " A condition is a state key, not an expression: write"
                    + " `show: <key>` and have the program compute the boolean with"
                    + " `vance.state.set(<key>, …)`. That keeps one expression language"
                    + " in the browser instead of two.");
        }

        // `title` and `label` are the same field. Both spellings read naturally
        // in different places (a page has a title, a button has a label) and
        // making the author remember which is which buys nothing.
        String label = str(raw.get("title"));
        if (label == null) label = str(raw.get("label"));

        String text = str(raw.get("text"));
        String from = str(raw.get("from"));
        String show = str(raw.get("show"));
        String region = region(raw.get("region"), depth, docPath, at);
        String id = str(raw.get("id"));
        if (id != null && !WIDGET_ID.matcher(id).matches()) {
            throw new ToolException(where(docPath, at) + ": `" + id + "` is not a widget id."
                    + " Letters, digits, `-` and `_`, starting with a letter — it is a name a"
                    + " program writes in `vance.view.patch(...)`.");
        }
        if (id != null && !budgetIds.add(id)) {
            // Two widgets with one id make a patch ambiguous, and the renderer
            // would silently apply it to whichever it reached first.
            throw new ToolException(where(docPath, at) + ": id `" + id + "` is used twice in"
                    + " this view. A program addresses a widget by id, so it has to name one.");
        }
        List<String> columns = strings(raw.get("columns"), docPath, at + ".columns");
        List<ViewOption> options = options(raw.get("options"), docPath, at + ".options");
        String variant = variant(raw.get("variant"), type, docPath, at);
        String mimeType = codeMime(raw.get("language"), type, docPath, at);
        boolean agent = agentFlag(raw.get("agent"), docPath, at);
        String accept = str(raw.get("accept"));
        if (accept != null && type != WidgetType.FILE) {
            throw new ToolException(where(docPath, at) + ": `accept` belongs to a `file`,"
                    + " not to a `" + type.wire() + "`.");
        }
        Map<String, ViewAction> on = handlers(raw.get("on"), docPath, at);

        List<FormFieldDto> fields = List.of();
        if (type == WidgetType.FORM || type == WidgetType.DETAILS) {
            rejectSettingFormKeys(raw.get("fields"), docPath, at + ".fields");
            fields = FormFieldYamlParser.parseFields(raw.get("fields"), where(docPath, at));
            if (fields.isEmpty()) {
                throw new ToolException(where(docPath, at) + ": a `" + type.wire()
                        + "` needs at least one entry under `fields`.");
            }
        } else if (raw.get("fields") != null) {
            throw new ToolException(where(docPath, at) + ": `fields` belongs to a `form` or a"
                    + " `details`, not to a `" + type.wire() + "`.");
        }

        // A separate check, not another `else if`: chained onto the branch above
        // it would never run for a `form`, so a stray `options:` on one would be
        // dropped without a word — the failure this parser exists to prevent.
        if (type != WidgetType.SELECT && raw.get("options") != null) {
            throw new ToolException(where(docPath, at) + ": `options` belongs to a `select`,"
                    + " not to a `" + type.wire() + "`.");
        }

        List<ViewNode> children =
                children(raw.get("children"), type, docPath, at, depth, budget, budgetIds);

        rejectRemovedKeys(raw, docPath, at);
        requireShape(type, label, text, from, show, options,
                raw.containsKey("options"), agent, on, docPath, at);

        return new ViewNode(type.wire(), label, text, from, id, region, show, columns, options,
                variant, mimeType, accept, fields, agent, on, children);
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

    /**
     * The {@code agent:} flag: may a chat beside the app trigger this action.
     *
     * <p>Only a real boolean. A string {@code "true"} is refused rather than
     * coerced, because this is the one field in a view where being wrong in the
     * permissive direction hands an agent a button nobody meant to give it —
     * and YAML would happily read {@code agent: yes-please} as a string.
     */
    private static boolean agentFlag(@Nullable Object raw, String docPath, String at) {
        if (raw == null) return false;
        if (raw instanceof Boolean b) return b;
        throw new ToolException(where(docPath, at) + ": `agent` is true or false, not `"
                + raw + "`. It says whether a chat beside the app may trigger this action.");
    }

    /** Per-widget requirements, in one place so the messages stay uniform. */
    private static void requireShape(WidgetType type, @Nullable String label,
                                     @Nullable String text, @Nullable String from,
                                     @Nullable String show, List<ViewOption> options,
                                     boolean optionsWritten, boolean agent,
                                     Map<String, ViewAction> on,
                                     String docPath, String at) {
        // `agent: true` on something with nothing to trigger is not harmless
        // noise: it reads as a granted permission, so the author believes an
        // agent can drive a widget that has no action at all.
        if (agent && on.isEmpty()) {
            throw new ToolException(where(docPath, at) + ": `agent: true` needs an action to"
                    + " trigger — put it on a widget that has `on:`.");
        }
        switch (type) {
            case TABLE -> {
                if (from == null) {
                    throw new ToolException(where(docPath, at) + ": a `table` needs `from`,"
                            + " the state key holding its rows.");
                }
            }
            case FORM, DETAILS -> {
                if (from == null) {
                    throw new ToolException(where(docPath, at) + ": a `" + type.wire()
                            + "` needs `from`, the state key holding the record it shows.");
                }
            }
            case INPUT, NUMBER, TOGGLE -> {
                if (from == null) {
                    throw new ToolException(where(docPath, at) + ": a `" + type.wire()
                            + "` needs `from`, the state key it reads and writes back.");
                }
            }
            case SELECT -> {
                if (from == null) {
                    throw new ToolException(where(docPath, at) + ": a `select` needs `from`,"
                            + " the state key it reads and writes back.");
                }
                // Absent and empty are different answers. Absent means the
                // author forgot: a select with no choices is an unusable
                // control, and saying so at parse time is the whole point of
                // this check. An explicit `options: []` means "the program
                // fills these" — which is the normal case once choices come
                // from documents (`ui.options(...)`), and there is nothing
                // honest to write in the document for it. Demanding a
                // placeholder there would put a wrong choice on screen until
                // `init()` has run.
                if (!optionsWritten) {
                    throw new ToolException(where(docPath, at) + ": a `select` needs `options`."
                            + " Write them as a list — `options: [open, paid]`, or"
                            + " `{value: paid, label: Bezahlt}` where the two differ."
                            + " For choices the program supplies, write `options: []`.");
                }
            }
            case BADGE, ALERT -> {
                if (text == null && from == null) {
                    throw new ToolException(where(docPath, at) + ": a `" + type.wire()
                            + "` needs `text` (a literal) or `from` (a state key).");
                }
            }
            case CODE -> {
                if (text == null && from == null) {
                    throw new ToolException(where(docPath, at) + ": a `code` needs `text`"
                            + " (a literal) or `from` (a state key holding the source).");
                }
            }
            case PAGINATION, FILE -> {
                if (from == null) {
                    throw new ToolException(where(docPath, at) + ": a `" + type.wire()
                            + "` needs `from`, the state key it reads and writes back.");
                }
            }
            case EMBED -> {
                // Same rule as text/markdown: a literal path or a state key
                // holding one. A widget with neither embeds nothing, which in a
                // generic renderer looks like a layout mistake.
                if (text == null && from == null) {
                    throw new ToolException(where(docPath, at) + ": an `embed` needs"
                            + " `text` (a document path) or `from` (a state key holding"
                            + " one).");
                }
            }
            case DIALOG -> {
                if (show == null) {
                    throw new ToolException(where(docPath, at) + ": a `dialog` needs `show`,"
                            + " the state key that opens and closes it. Without one there"
                            + " would be no way to close it — the program sets the key to"
                            + " true to open and false to close.");
                }
            }
            case REPEAT -> {
                if (from == null) {
                    throw new ToolException(where(docPath, at) + ": a `repeat` needs"
                            + " `from`, the state key holding the list it repeats over.");
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
                                           int[] budget, java.util.Set<String> budgetIds) {
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
            out.add(node(m, docPath, childAt, depth + 1, budget, budgetIds));
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

    /**
     * The drawing surface's height, and it may only be asked for once.
     *
     * <p>Refused below the root, because there is exactly one surface — the
     * guest's own document — and it cannot be moved to where a nested widget
     * sits. Accepting the key there and rendering elsewhere would be the
     * "almost right" this parser exists to prevent.
     */
    private static @Nullable String region(@Nullable Object raw, int depth, String docPath,
                                           String at) {
        if (raw == null) return null;
        if (depth > 0) {
            throw new ToolException(where(docPath, at) + ": `region` belongs on the view's"
                    + " root, not on a widget inside it. There is one drawing surface per view"
                    + " and it cannot be moved into the tree.");
        }
        String value = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if (REGION_FILL.equals(value)) return REGION_FILL;
        try {
            int px = Integer.parseInt(value);
            if (px > 0 && px <= MAX_REGION_PX) return String.valueOf(px);
        } catch (NumberFormatException ignored) {
            // Falls through to the message below, which names both spellings.
        }
        throw new ToolException(where(docPath, at) + ": `region` is a height in pixels"
                + " (1–" + MAX_REGION_PX + ") or `" + REGION_FILL + "` for the rest of the"
                + " space. Got `" + raw + "`.");
    }

    private static @Nullable String variant(@Nullable Object raw, WidgetType type,
                                            String docPath, String at) {
        String value = str(raw);
        if (value == null) return null;
        if (type != WidgetType.ALERT && type != WidgetType.BADGE) {
            throw new ToolException(where(docPath, at) + ": `variant` belongs to an `alert`"
                    + " or a `badge`, not to a `" + type.wire() + "`.");
        }
        String norm = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!VARIANTS.contains(norm)) {
            throw new ToolException(where(docPath, at) + ": unknown `variant` `" + value
                    + "`. One of: " + String.join(", ", VARIANTS) + ".");
        }
        return norm;
    }

    private static @Nullable String codeMime(@Nullable Object raw, WidgetType type,
                                             String docPath, String at) {
        String value = str(raw);
        if (value == null) return null;
        if (type != WidgetType.CODE) {
            throw new ToolException(where(docPath, at) + ": `language` belongs to a `code`,"
                    + " not to a `" + type.wire() + "`.");
        }
        String norm = value.trim().toLowerCase(java.util.Locale.ROOT);
        String mime = CODE_LANGUAGES.get(norm);
        if (mime == null) {
            throw new ToolException(where(docPath, at) + ": unknown `language` `" + value
                    + "`. One of: "
                    + String.join(", ", new java.util.TreeSet<>(CODE_LANGUAGES.keySet())) + ".");
        }
        return mime;
    }

    /**
     * Read a {@code select}'s choices.
     *
     * <p>A bare scalar is a value that is also its own caption; a mapping
     * separates the two. Anything else is refused rather than coerced: an
     * option list is short and hand-written, so a nested list in there is a
     * mistake worth naming, not something to flatten quietly.
     */
    private static List<ViewOption> options(@Nullable Object raw, String docPath, String at) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new ToolException(where(docPath, at) + ": expected a list of choices.");
        }
        List<ViewOption> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            String entryAt = at + "[" + i + "]";
            if (entry instanceof Map<?, ?> map) {
                String value = str(map.get("value"));
                if (value == null) {
                    throw new ToolException(where(docPath, entryAt) + ": needs a `value`.");
                }
                out.add(new ViewOption(value, str(map.get("label"))));
                continue;
            }
            String value = str(entry);
            if (value == null) {
                throw new ToolException(where(docPath, entryAt) + ": expected a value or a"
                        + " `{value, label}` mapping.");
            }
            out.add(new ViewOption(value, value));
        }
        return List.copyOf(out);
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
