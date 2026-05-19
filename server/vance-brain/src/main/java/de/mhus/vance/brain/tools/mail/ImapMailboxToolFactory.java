package de.mhus.vance.brain.tools.mail;

import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.mail.ImapClient;
import de.mhus.vance.toolpack.mail.ImapConfig;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code imap_mailbox} — emits three sub-tools per configured pack:
 * {@code <name>__list_folders}, {@code <name>__list_messages},
 * {@code <name>__get_message}. The pack-level config carries the IMAP
 * host + auth; each invocation resolves secret templates at call time
 * via {@link SettingsSecretResolver}, so per-user / per-project secret
 * scopes work naturally.
 *
 * <p>Sub-tools are <i>not</i> primary by default — IMAP packs typically
 * tag with {@code mail}/{@code @side-effect} and the recipe filter
 * keeps them in the discovery block. Project-scoped shared mailboxes
 * (sales@, support@) override that at the pack level.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImapMailboxToolFactory implements ToolFactory {

    public static final String TYPE_ID = "imap_mailbox";

    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("host"),
            "properties", Map.of(
                    "host", Map.of("type", "string", "description", "IMAP server hostname."),
                    "port", Map.of("type", "integer", "description", "Default: 993 (TLS) or 143 (plain)."),
                    "tls", Map.of("type", "boolean", "description", "Implicit-TLS connect (default true)."),
                    "starttls", Map.of("type", "boolean", "description", "STARTTLS upgrade (default false)."),
                    "user", Map.of("type", "string"),
                    "password", Map.of("type", "string"),
                    "defaultFolder", Map.of("type", "string", "description", "Default INBOX.")));

    private final SettingsSecretResolver secretResolver;

    @Override public String typeId() { return TYPE_ID; }
    @Override public Map<String, Object> parametersSchema() { return PARAMETERS_SCHEMA; }

    @Override
    public Collection<Tool> create(ServerToolDocument document) {
        return create(document, null);
    }

    @Override
    public Collection<Tool> create(
            ServerToolDocument document, @Nullable ToolInvocationContext ctx) {
        String packName = document.getName();
        Set<String> labels = labelsFor(document);
        String promptHint = document.getPromptHint() == null ? "" : document.getPromptHint();
        boolean primary = document.isPrimary();
        boolean deferred = document.isDefaultDeferred();

        List<Tool> out = new ArrayList<>(3);
        out.add(new ListFoldersTool(packName, document.getParameters(), labels, primary, deferred, promptHint));
        out.add(new ListMessagesTool(packName, document.getParameters(), labels, primary, deferred, promptHint));
        out.add(new GetMessageTool(packName, document.getParameters(), labels, primary, deferred, promptHint));
        log.info("ImapMailboxToolFactory pack='{}' tenant='{}' project='{}' produced 3 tools",
                packName, document.getTenantId(), document.getProjectId());
        return out;
    }

    private static Set<String> labelsFor(ServerToolDocument doc) {
        Set<String> out = new LinkedHashSet<>();
        if (doc.getLabels() != null) out.addAll(doc.getLabels());
        out.add("mail");
        out.add("imap");
        out.add(TYPE_ID + ":" + doc.getName());
        out.add("read-only");
        return Set.copyOf(out);
    }

    // ──────────────────── Tools ────────────────────

    private abstract class BaseImapTool implements Tool {
        final String name;
        final Map<String, Object> rawParams;
        final Set<String> labels;
        final boolean primary;
        final boolean deferred;
        final String promptHint;

        BaseImapTool(String packName, String suffix, Map<String, Object> raw,
                     Set<String> labels, boolean primary, boolean deferred, String hint) {
            this.name = packName + "__" + suffix;
            this.rawParams = raw == null ? Map.of() : raw;
            this.labels = labels;
            this.primary = primary;
            this.deferred = deferred;
            this.promptHint = hint;
        }

        @Override public String name() { return name; }
        @Override public boolean primary() { return primary; }
        @Override public boolean deferred() { return deferred; }
        @Override public Set<String> labels() { return labels; }
        @Override public String promptHint() { return promptHint; }

        /** Build a per-invocation config: resolve secret templates with the calling ctx. */
        ImapClient resolveClient(ToolInvocationContext ctx) {
            Map<String, Object> resolved = new LinkedHashMap<>(rawParams.size());
            for (Map.Entry<String, Object> e : rawParams.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String s) {
                    resolved.put(e.getKey(), secretResolver.resolve(s, ctx));
                } else {
                    resolved.put(e.getKey(), v);
                }
            }
            return new ImapClient(ImapConfig.fromParameters(resolved));
        }
    }

    private class ListFoldersTool extends BaseImapTool {
        ListFoldersTool(String pack, Map<String, Object> raw, Set<String> labels, boolean p, boolean d, String h) {
            super(pack, "list_folders", raw, labels, p, d, h);
        }

        @Override public String description() {
            return "List IMAP folder names visible to the configured user. No arguments.";
        }

        @Override public Map<String, Object> paramsSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            try {
                List<String> folders = resolveClient(ctx).listFolders();
                return Map.of("folders", folders, "count", folders.size());
            } catch (RuntimeException ex) {
                throw new ToolException("list_folders failed: " + ex.getMessage(), ex);
            }
        }
    }

    private class ListMessagesTool extends BaseImapTool {
        ListMessagesTool(String pack, Map<String, Object> raw, Set<String> labels, boolean p, boolean d, String h) {
            super(pack, "list_messages", raw, labels, p, d, h);
        }

        @Override public String description() {
            return "List message headers (subject/from/to/date/seen-flag) in a folder. "
                    + "Args: folder (default: pack's defaultFolder), limit (1-500, default 50), "
                    + "unread_only (default false), since (ISO-8601 instant — optional cutoff).";
        }

        @Override public Map<String, Object> paramsSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "folder", Map.of("type", "string"),
                            "limit", Map.of("type", "integer", "minimum", 1, "maximum", 500),
                            "unread_only", Map.of("type", "boolean"),
                            "since", Map.of("type", "string",
                                    "description", "ISO-8601 instant, e.g. 2026-05-19T00:00:00Z")));
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            if (params == null) params = Map.of();
            String folder = stringOrNull(params.get("folder"));
            int limit = intOrDefault(params.get("limit"), 50);
            boolean unread = boolOrDefault(params.get("unread_only"), false);
            Instant since = parseInstant(params.get("since"));
            try {
                List<Map<String, Object>> rows = resolveClient(ctx)
                        .listMessages(folder, limit, unread, since);
                return Map.of("messages", rows, "count", rows.size());
            } catch (RuntimeException ex) {
                throw new ToolException("list_messages failed: " + ex.getMessage(), ex);
            }
        }
    }

    private class GetMessageTool extends BaseImapTool {
        GetMessageTool(String pack, Map<String, Object> raw, Set<String> labels, boolean p, boolean d, String h) {
            super(pack, "get_message", raw, labels, p, d, h);
        }

        @Override public String description() {
            return "Full envelope + body for one message. Args: messageRef (folder index or Message-ID), "
                    + "folder (optional, defaults to pack's defaultFolder).";
        }

        @Override public Map<String, Object> paramsSchema() {
            return Map.of(
                    "type", "object",
                    "required", List.of("messageRef"),
                    "properties", Map.of(
                            "messageRef", Map.of("type", "string",
                                    "description", "Folder index (1-based) or Message-ID header value"),
                            "folder", Map.of("type", "string")));
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            if (params == null) params = Map.of();
            String ref = stringOrNull(params.get("messageRef"));
            if (ref == null) throw new ToolException("missing required 'messageRef'");
            String folder = stringOrNull(params.get("folder"));
            try {
                return resolveClient(ctx).getMessage(folder, ref);
            } catch (RuntimeException ex) {
                throw new ToolException("get_message failed: " + ex.getMessage(), ex);
            }
        }
    }

    // ─── helpers ───

    private static @Nullable String stringOrNull(@Nullable Object v) {
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static int intOrDefault(@Nullable Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException ignored) { return def; }
        }
        return def;
    }

    private static boolean boolOrDefault(@Nullable Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return "true".equalsIgnoreCase(s.trim());
        return def;
    }

    private static @Nullable Instant parseInstant(@Nullable Object v) {
        if (!(v instanceof String s) || s.isBlank()) return null;
        try { return Instant.parse(s.trim()); }
        catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("'since' must be ISO-8601 instant: " + ex.getMessage());
        }
    }
}
