package de.mhus.vance.brain.script.cortex;

import org.jspecify.annotations.Nullable;

/**
 * Maps a Script-Cortex document path to a mime-type when the create
 * request did not supply one. Drives both the inline-storage decision
 * in {@link de.mhus.vance.shared.document.DocumentService} (textual
 * mimes go inline) and the CodeMirror language selection in the
 * Web-UI.
 */
public final class ScriptMimeResolver {

    public static final String KIND_SCRIPT = "script";

    private ScriptMimeResolver() {}

    public static String resolve(@Nullable String path, @Nullable String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (path == null) return "text/plain";
        String lower = path.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return "text/plain";
        String ext = lower.substring(dot + 1);
        return switch (ext) {
            case "js", "mjs" -> "text/javascript";
            case "json" -> "application/json";
            case "md", "markdown" -> "text/markdown";
            case "yaml", "yml" -> "application/yaml";
            case "txt" -> "text/plain";
            default -> "text/plain";
        };
    }

    /** True when the file is executable through the script executor.
     *  Currently only {@code .js} (and {@code .mjs} as an alias). */
    public static boolean isExecutable(@Nullable String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".js") || lower.endsWith(".mjs");
    }
}
