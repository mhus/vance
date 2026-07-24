package de.mhus.vance.toolpack.mail;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed view of an {@code smtp_sender}-pack
 * {@code ServerToolDocument.parameters} block.
 *
 * <p>YAML schema:
 * <pre>
 *   parameters:
 *     host: "smtp.example.com"
 *     port: 587                                # default 587 (STARTTLS) or 465 (TLS)
 *     tls: false                                # implicit-TLS on port 465
 *     starttls: true                            # STARTTLS upgrade on port 587 (default)
 *     user: "{{secret:project:smtp.user}}"
 *     password: "{{secret:project:smtp.password}}"
 *     from: "noreply@example.com"               # default From: header
 *     timeoutSeconds: 30
 * </pre>
 */
public record SmtpConfig(
        String host,
        int port,
        boolean tls,
        boolean starttls,
        String user,
        String password,
        String from,
        int timeoutSeconds,
        /** When non-empty, every recipient's domain must be listed (abuse guard). */
        java.util.List<String> allowedRecipientDomains,
        /** When non-empty, the effective From: must be listed (anti-spoofing). */
        java.util.List<String> allowedFrom) {

    public static final int DEFAULT_STARTTLS_PORT = 587;
    public static final int DEFAULT_TLS_PORT = 465;
    public static final int DEFAULT_PLAIN_PORT = 25;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    public SmtpConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("smtp_sender: 'host' is required");
        }
        if (from == null) from = "";
        if (user == null) user = "";
        if (password == null) password = "";
        allowedRecipientDomains = allowedRecipientDomains == null
                ? java.util.List.of() : java.util.List.copyOf(allowedRecipientDomains);
        allowedFrom = allowedFrom == null
                ? java.util.List.of() : java.util.List.copyOf(allowedFrom);
    }

    public static SmtpConfig fromParameters(@Nullable Map<String, Object> params) {
        if (params == null) params = Map.of();
        String host = stringOrThrow(params.get("host"), "host");
        boolean tls = boolWithDefault(params.get("tls"), false);
        boolean starttls = boolWithDefault(params.get("starttls"), !tls);
        int defaultPort = tls ? DEFAULT_TLS_PORT : (starttls ? DEFAULT_STARTTLS_PORT : DEFAULT_PLAIN_PORT);
        int port = intOrDefault(params.get("port"), defaultPort);
        String user = stringOrEmpty(params.get("user"));
        String password = stringOrEmpty(params.get("password"));
        String from = stringOrEmpty(params.get("from"));
        int timeout = intOrDefault(params.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS);
        return new SmtpConfig(host, port, tls, starttls, user, password, from, timeout,
                stringList(params.get("allowedRecipientDomains")),
                stringList(params.get("allowedFrom")));
    }

    /** Parse a config value that may be a YAML list or a comma-separated string. */
    private static java.util.List<String> stringList(@Nullable Object v) {
        if (v instanceof java.util.List<?> list) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            }
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String part : s.split(",")) {
                if (!part.isBlank()) out.add(part.trim());
            }
            return out;
        }
        return java.util.List.of();
    }

    /** "smtps" for implicit-TLS on 465; "smtp" for plain or STARTTLS upgrade. */
    public String protocol() {
        return tls ? "smtps" : "smtp";
    }

    // ─── helpers ───

    private static String stringOrThrow(@Nullable Object v, String field) {
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("smtp_sender: '" + field + "' is required");
        }
        return s.trim();
    }

    private static String stringOrEmpty(@Nullable Object v) {
        return v instanceof String s ? s : "";
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
