package de.mhus.vance.toolpack.mail;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed view of an {@code imap_mailbox}-pack {@code ServerToolDocument.parameters}
 * block. Built once per doc-update by the brain-side factory; cheap
 * enough to discard and rebuild on cache miss.
 *
 * <p>YAML schema:
 * <pre>
 *   parameters:
 *     host: "imap.example.com"                 # or templated
 *     port: 993                                 # default 993 (implicit-TLS)
 *     tls: true                                 # default true → implicit-TLS
 *     starttls: false                           # default false. If true, host
 *                                               # uses STARTTLS upgrade on a
 *                                               # plain port (typically 143).
 *     user: "{{secret:project:imap.user}}"
 *     password: "{{secret:project:imap.password}}"
 *     defaultFolder: "INBOX"                    # default "INBOX"
 *     timeoutSeconds: 30
 *     readonly: true                            # default true. false enables
 *                                               # set_seen / set_flagged /
 *                                               # move_message / delete_message
 *                                               # sub-tools.
 *     trashFolder: "Trash"                      # default "Trash". Target of
 *                                               # soft-delete (delete_message
 *                                               # without hard=true).
 * </pre>
 *
 * <p>The {@code {{secret:...}}} references are resolved at invoke-time,
 * not at config-build-time, so config objects are safe to log / cache.
 */
public record ImapConfig(
        String host,
        int port,
        boolean tls,
        boolean starttls,
        String user,
        String password,
        String defaultFolder,
        int timeoutSeconds,
        boolean readonly,
        String trashFolder) {

    public static final int DEFAULT_TLS_PORT = 993;
    public static final int DEFAULT_PLAIN_PORT = 143;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final String DEFAULT_FOLDER = "INBOX";
    public static final String DEFAULT_TRASH_FOLDER = "Trash";

    public ImapConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("imap_mailbox: 'host' is required");
        }
        if (defaultFolder == null || defaultFolder.isBlank()) defaultFolder = DEFAULT_FOLDER;
        if (trashFolder == null || trashFolder.isBlank()) trashFolder = DEFAULT_TRASH_FOLDER;
        if (user == null) user = "";
        if (password == null) password = "";
    }

    public static ImapConfig fromParameters(@Nullable Map<String, Object> params) {
        if (params == null) params = Map.of();

        String host = stringOrThrow(params.get("host"), "host");
        boolean tls = boolWithDefault(params.get("tls"), true);
        boolean starttls = boolWithDefault(params.get("starttls"), false);
        int port = intOrDefault(
                params.get("port"),
                tls && !starttls ? DEFAULT_TLS_PORT : DEFAULT_PLAIN_PORT);
        String user = stringOrEmpty(params.get("user"));
        String password = stringOrEmpty(params.get("password"));
        String folder = stringOrDefault(params.get("defaultFolder"), DEFAULT_FOLDER);
        int timeout = intOrDefault(params.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS);
        boolean readonly = boolWithDefault(params.get("readonly"), true);
        String trash = stringOrDefault(params.get("trashFolder"), DEFAULT_TRASH_FOLDER);

        return new ImapConfig(host, port, tls, starttls, user, password, folder, timeout, readonly, trash);
    }

    /** "imaps" for implicit-TLS, "imap" for plain/STARTTLS. Drives Jakarta Mail provider lookup. */
    public String protocol() {
        return tls && !starttls ? "imaps" : "imap";
    }

    // ─── helpers ───

    private static String stringOrThrow(@Nullable Object v, String field) {
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    "imap_mailbox: '" + field + "' is required");
        }
        return s.trim();
    }

    private static String stringOrEmpty(@Nullable Object v) {
        return v instanceof String s ? s : "";
    }

    private static String stringOrDefault(@Nullable Object v, String def) {
        return v instanceof String s && !s.isBlank() ? s.trim() : def;
    }

    private static boolean boolWithDefault(@Nullable Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return "true".equalsIgnoreCase(s.trim());
        return def;
    }

    private static int intOrDefault(@Nullable Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException ignored) { return def; }
        }
        return def;
    }
}
