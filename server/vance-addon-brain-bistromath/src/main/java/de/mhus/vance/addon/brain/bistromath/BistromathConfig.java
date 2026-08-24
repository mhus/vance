package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
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
        @Nullable String init) {

    /** App discriminator and manifest block key. */
    public static final String BLOCK = "custom";

    /** {@code $meta.kind} of a view document. */
    public static final String VIEW_KIND = "app-view";

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
        return new BistromathConfig(null, null);
    }

    public static BistromathConfig from(ApplicationDocument manifest) {
        Object raw = manifest.config().get(BLOCK);
        if (raw == null) return empty();
        if (!(raw instanceof Map<?, ?> map)) {
            // Named as it appears in the document (top-level `custom:`), not as
            // the logical path `config.custom` — the reader has the file open.
            throw new ToolException("Manifest block `" + BLOCK + "` is not a mapping.");
        }
        return new BistromathConfig(optional(map.get("landing")), optional(map.get("init")));
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
        return out;
    }

    public static boolean isValidHandle(String handle) {
        return HANDLE.matcher(handle).matches();
    }

    private static @Nullable String optional(@Nullable Object v) {
        if (v == null) return null;
        String s = v instanceof String str ? str : String.valueOf(v);
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
