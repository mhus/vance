package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The {@code custom} block of a Bistromath app manifest — two optional keys,
 * and that is the whole schema.
 *
 * <pre>
 * $meta:
 *   kind: application
 *   app: custom
 * title: Invoice register
 * custom:
 *   landing: list        # optional; else the first view alphabetically
 *   init: setup.js       # optional; else main.js
 * </pre>
 *
 * <p><b>What used to be here and why it is gone.</b> The first build declared
 * {@code views[]}, {@code tables[]}, {@code scripts[]} and a
 * {@code schemaVersion}. Every one of them was a registry over something that
 * is already addressable: a view says what it is in its own header
 * ({@code $meta.kind: app-view}), a data folder is named by the script that
 * reads it, and the program is {@code main.js} by convention. The registries
 * bought renameability and column order and cost a declaration step for every
 * app — including a create dialog that asked for a database table from apps
 * that have no data at all.
 *
 * <p>The block sits at the <b>top level</b> of the document, not under a
 * {@code config:} key. {@link ApplicationDocument#config()} is a logical
 * grouping: {@code ApplicationCodec} hoists every top-level map into it on
 * read and writes it back flat on serialise. A literal {@code config:} would
 * be read as a block <em>called</em> "config" and the manifest would parse as
 * silently empty.
 *
 * <p>Unknown keys are ignored rather than refused. A manifest written by an
 * older build carries {@code views:} and {@code tables:}; dropping them
 * quietly is right, because the app is still perfectly describable without
 * them — what it cannot do is keep views that live where the old build put
 * them without the new header.
 */
public record BistromathConfig(
        @Nullable String landing,
        @Nullable String init,
        /**
         * Libraries this app needs, as {@code name@version}.
         *
         * <p>One of three places requires can be written, and the one that says
         * "the app as a whole". A view's own {@code required:} says "this screen",
         * and a {@code @require} in a script header says "this file" — see
         * {@link RequireResolver}. All three end up in one ordered load list,
         * because the guest has one global scope and no module system.
         */
        List<String> required) {

    public BistromathConfig {
        if (required == null) required = List.of();
    }

    /** App discriminator and manifest block key. */
    public static final String BLOCK = "custom";

    /** {@code $meta.kind} of a view document. */
    public static final String VIEW_KIND = "app-view";

    /**
     * Header directive that marks an app-local script.
     *
     * <p>A marker in the file and not a {@code $meta.kind}, and that was a
     * correction: there are header strategies for Markdown, YAML and JSON only,
     * so a {@code .js} document <b>cannot</b> carry a kind. The analogy to
     * {@link #VIEW_KIND} — a view declares itself — was right in principle and
     * wrong in mechanism.
     *
     * <p>Two alternatives were rejected. Loading *every* {@code .js} in the
     * folder makes a note somebody left lying around part of the program.
     * Listing them in the manifest is the registry §4.1 removed, and it would
     * need a path syntax the schema does not otherwise have. A marker keeps the
     * declaration in the file, next to the {@code @require} lines the author is
     * writing anyway — and a file that says nothing stays out.
     */
    public static final String SCRIPT_MARKER = "app-script";

    /** The manifest, as it is called on disk. */
    public static final String MANIFEST_NAME = "_app.yaml";

    /** The program, unless {@link #init} names another file. */
    public static final String DEFAULT_PROGRAM = "main.js";

    /**
     * A view handle travels in a URL query and in an {@code AppTarget}, whose
     * grammar forbids {@code |}. Since the handle is now the file name, this
     * is a constraint on file names — a document that breaks it is reported,
     * not silently turned into an unreachable view.
     */
    static final Pattern HANDLE = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    public static BistromathConfig empty() {
        return new BistromathConfig(null, null, List.of());
    }

    public static BistromathConfig from(ApplicationDocument manifest) {
        Object raw = manifest.config().get(BLOCK);
        if (raw == null) return empty();
        if (!(raw instanceof Map<?, ?> map)) {
            // Named as it appears in the document (top-level `custom:`), not as
            // the logical path `config.custom` — the reader has the file open.
            throw new ToolException("Manifest block `" + BLOCK + "` is not a mapping.");
        }
        return new BistromathConfig(optional(map.get("landing")), optional(map.get("init")),
                requiredList(map.get("required")));
    }

    /** The program path, relative to the app folder. */
    public String program() {
        return init == null ? DEFAULT_PROGRAM : init;
    }

    /** Serialise back. Absent keys stay absent — an empty block is valid. */
    public Map<String, Object> toBlock() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (landing != null) out.put("landing", landing);
        if (init != null) out.put("init", init);
        if (!required.isEmpty()) out.put("required", List.copyOf(required));
        return out;
    }

    public static boolean isValidHandle(String handle) {
        return HANDLE.matcher(handle).matches();
    }

    /** A single string is accepted as a one-element list — the common case. */
    private static List<String> requiredList(@Nullable Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof String s) {
            String t = s.trim();
            return t.isEmpty() ? List.of() : List.of(t);
        }
        if (!(raw instanceof List<?> list)) {
            throw new ToolException("Manifest key `required` is neither a list nor a string.");
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o == null) continue;
            String t = String.valueOf(o).trim();
            if (!t.isEmpty()) out.add(t);
        }
        return List.copyOf(out);
    }

    private static @Nullable String optional(@Nullable Object v) {
        if (v == null) return null;
        String s = v instanceof String str ? str : String.valueOf(v);
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
