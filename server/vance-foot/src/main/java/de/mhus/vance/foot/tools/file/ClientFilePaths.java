package de.mhus.vance.foot.tools.file;

import java.nio.file.Path;

/**
 * Path-string normalisation for client file tools. Expands a leading
 * {@code "~"} or {@code "~/…"} to the user's home directory — the
 * shell would do this implicitly, but Java's {@code Path.of(...)}
 * treats {@code "~"} as a literal segment, so the LLM's intent gets
 * lost without a deliberate expansion here.
 */
final class ClientFilePaths {

    private ClientFilePaths() {}

    static Path resolve(String raw) {
        if (raw == null || raw.isEmpty()) return Path.of("");
        String expanded = expandHome(raw);
        return Path.of(expanded);
    }

    private static String expandHome(String raw) {
        if (raw.equals("~")) {
            return System.getProperty("user.home");
        }
        if (raw.startsWith("~/")) {
            return System.getProperty("user.home") + raw.substring(1);
        }
        return raw;
    }
}
