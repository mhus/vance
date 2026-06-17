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
 * {@code imap_mailbox} — emits four read sub-tools per configured pack
 * ({@code <name>__list_folders}, {@code <name>__list_messages},
 * {@code <name>__get_message}, {@code <name>__preview_message}) plus,
 * when the pack sets {@code readonly: false}, four write sub-tools
 * ({@code <name>__set_seen}, {@code <name>__set_flagged},
 * {@code <name>__move_message}, {@code <name>__delete_message}).
 *
 * <p>{@code preview_message} is the triage-oriented variant: HTML
 * bodies are stripped to plain text via jsoup and capped by the pack's
 * {@code bodyMaxBytes} (default 64 KiB) so Zoho/Gmail HTML mails with
 * inline-image CIDs and embedded CSS don't blow up the token budget. The pack-level config carries the IMAP
 * host + auth; each invocation resolves secret templates at call time
 * via {@link SettingsSecretResolver}, so per-user / per-project secret
 * scopes work naturally.
 *
 * <p>Sub-tools are <i>not</i> primary by default — IMAP packs typically
 * tag with {@code mail}/{@code @side-effect} and the recipe filter
 * keeps them in the discovery block. Project-scoped shared mailboxes
 * (sales@, support@) override that at the pack level.
 *
 * <p>Read tools carry the {@code read-only} label; write tools carry
 * {@code write} + {@code side-effect}. {@code readonly} is read at
 * factory-build time from the document parameters (no secret-template
 * indirection) — a pack is either read-only or read+write, not both.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImapMailboxToolFactory implements ToolFactory {

    public static final String TYPE_ID = "imap_mailbox";

    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("host"),
            "properties", Map.ofEntries(
                    Map.entry("host", Map.of("type", "string", "description", "IMAP server hostname.")),
                    Map.entry("port", Map.of("type", "integer", "description", "Default: 993 (TLS) or 143 (plain).")),
                    Map.entry("tls", Map.of("type", "boolean", "description", "Implicit-TLS connect (default true).")),
                    Map.entry("starttls", Map.of("type", "boolean", "description", "STARTTLS upgrade (default false).")),
                    Map.entry("user", Map.of("type", "string")),
                    Map.entry("password", Map.of("type", "string")),
                    Map.entry("defaultFolder", Map.of("type", "string", "description", "Default INBOX.")),
                    Map.entry("readonly", Map.of("type", "boolean",
                            "description", "Default true. False enables set_seen/set_flagged/move_message/delete_message.")),
                    Map.entry("trashFolder", Map.of("type", "string",
                            "description", "Soft-delete target. Default 'Trash'.")),
                    Map.entry("bodyMaxBytes", Map.of("type", "integer",
                            "description", "Default 65536. Caps preview_message body after HTML-stripping. 0 = unlimited."))));

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
        Map<String, Object> params = document.getParameters();
        boolean readonly = readonlyFlag(params);
        Set<String> readLabels = labelsFor(document, /* write */ false);
        Set<String> writeLabels = readonly ? Set.of() : labelsFor(document, /* write */ true);
        String promptHint = document.getPromptHint() == null ? "" : document.getPromptHint();
        boolean primary = document.isPrimary();
        boolean deferred = document.isDefaultDeferred();

        List<Tool> out = new ArrayList<>(readonly ? 4 : 8);
        out.add(new ListFoldersTool(packName, params, readLabels, primary, deferred, promptHint));
        out.add(new ListMessagesTool(packName, params, readLabels, primary, deferred, promptHint));
        out.add(new GetMessageTool(packName, params, readLabels, primary, deferred, promptHint));
        out.add(new PreviewMessageTool(packName, params, readLabels, primary, deferred, promptHint));
        if (!readonly) {
            out.add(new SetSeenTool(packName, params, writeLabels, primary, deferred, promptHint));
            out.add(new SetFlaggedTool(packName, params, writeLabels, primary, deferred, promptHint));
            out.add(new MoveMessageTool(packName, params, writeLabels, primary, deferred, promptHint));
            out.add(new DeleteMessageTool(packName, params, writeLabels, primary, deferred, promptHint));
        }
        log.info("ImapMailboxToolFactory pack='{}' tenant='{}' project='{}' readonly={} produced {} tools",
                packName, document.getTenantId(), document.getProjectId(), readonly, out.size());
        return out;
    }

    /** {@code readonly} default true — write tools opt-in per pack. */
    private static boolean readonlyFlag(@Nullable Map<String, Object> params) {
        if (params == null) return true;
        Object v = params.get("readonly");
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return !"false".equalsIgnoreCase(s.trim());
        return true;
    }

    private static Set<String> labelsFor(ServerToolDocument doc, boolean write) {
        Set<String> out = new LinkedHashSet<>();
        if (doc.getLabels() != null) out.addAll(doc.getLabels());
        out.add("mail");
        out.add("imap");
        out.add(TYPE_ID + ":" + doc.getName());
        if (write) {
            out.add("write");
            out.add("side-effect");
        } else {
            out.add("read-only");
        }
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

    private class PreviewMessageTool extends BaseImapTool {
        PreviewMessageTool(String pack, Map<String, Object> raw, Set<String> labels, boolean p, boolean d, String h) {
            super(pack, "preview_message", raw, labels, p, d, h);
        }

        @Override public String description() {
            return "Triage-oriented body view: HTML stripped to plain text (jsoup), "
                    + "capped at the pack's bodyMaxBytes (default 64 KiB). Returns "
                    + "envelope + body + bodyOriginalChars + bodyTruncated + "
                    + "bodyStrippedFromHtml. Use this for 'is this worth reading' "
                    + "decisions; use get_message for raw bodies (link extraction etc.). "
                    + "Args: messageRef (folder index or Message-ID), folder (optional), "
                    + "maxBytes (optional override of pack default; 0 = unlimited).";
        }

        @Override public Map<String, Object> paramsSchema() {
            return Map.of(
                    "type", "object",
                    "required", List.of("messageRef"),
                    "properties", Map.of(
                            "messageRef", Map.of("type", "string"),
                            "folder", Map.of("type", "string"),
                            "maxBytes", Map.of("type", "integer", "minimum", 0)));
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            if (params == null) params = Map.of();
            String ref = stringOrNull(params.get("messageRef"));
            if (ref == null) throw new ToolException("missing required 'messageRef'");
            String folder = stringOrNull(params.get("folder"));
            int maxBytes = intOrDefault(params.get("maxBytes"), -1);
            try {
                return resolveClient(ctx).previewMessage(folder, ref, maxBytes);
            } catch (RuntimeException ex) {
                throw new ToolException("preview_message failed: " + ex.getMessage(), ex);
            }
        }
    }

    private class SetSeenTool extends BaseImapTool {
        SetSeenTool(String pack, Map<String, Object> raw, Set<String> labels, boolean p, boolean d, String h) {
            super(pack, "set_seen", raw, labels, p, d, h);
        }

        @Override public String description() {
            return "Set or unset the \\Seen flag (read / unread). "
                    + "Args: messageRef (folder index or Message-ID), seen (boolean, default true), "
                    + "folder (optional, defaults to pack's defaultFolder).";
        }

        @Override public Map<String, Object> paramsSchema() {
            return Map.of(
                    "type", "object",
                    "required", List.of("messageRef"),
                    "properties", Map.of(
                            "messageRef", Map.of("type", "string"),
                            "seen", Map.of("type", "boolean"),
                            "folder", Map.of("type", "string")));
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            if (params == null) params = Map.of();
            String ref = stringOrNull(params.get("messageRef"));
            if (ref == null) throw new ToolException("missing required 'messageRef'");
            boolean seen = boolOrDefault(params.get("seen"), true);
            String folder = stringOrNull(params.get("folder"));
            try {
                return resolveClient(ctx).setSeen(folder, ref, seen);
            } catch (RuntimeException ex) {
                throw new ToolException("set_seen failed: " + ex.getMessage(), ex);
            }
        }
    }

    private class SetFlaggedTool extends BaseImapTool {
        SetFlaggedTool(String pack, Map<String, Object> raw, Set<String> labels, boolean p, boolean d, String h) {
            super(pack, "set_flagged", raw, labels, p, d, h);
        }

        @Override public String description() {
            return "Set or unset the \\Flagged flag (star / important). "
                    + "Args: messageRef, flagged (boolean, default true), folder (optional).";
        }

        @Override public Map<String, Object> paramsSchema() {
            return Map.of(
                    "type", "object",
                    "required", List.of("messageRef"),
                    "properties", Map.of(
                            "messageRef", Map.of("type", "string"),
                            "flagged", Map.of("type", "boolean"),
                            "folder", Map.of("type", "string")));
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            if (params == null) params = Map.of();
            String ref = stringOrNull(params.get("messageRef"));
            if (ref == null) throw new ToolException("missing required 'messageRef'");
            boolean flagged = boolOrDefault(params.get("flagged"), true);
            String folder = stringOrNull(params.get("folder"));
            try {
                return resolveClient(ctx).setFlagged(folder, ref, flagged);
            } catch (RuntimeException ex) {
                throw new ToolException("set_flagged failed: " + ex.getMessage(), ex);
            }
        }
    }

    private class MoveMessageTool extends BaseImapTool {
        MoveMessageTool(String pack, Map<String, Object> raw, Set<String> labels, boolean p, boolean d, String h) {
            super(pack, "move_message", raw, labels, p, d, h);
        }

        @Override public String description() {
            return "Move a message to another folder (COPY + \\Deleted + EXPUNGE). "
                    + "Args: messageRef, targetFolder (required, must exist), folder (source, optional).";
        }

        @Override public Map<String, Object> paramsSchema() {
            return Map.of(
                    "type", "object",
                    "required", List.of("messageRef", "targetFolder"),
                    "properties", Map.of(
                            "messageRef", Map.of("type", "string"),
                            "targetFolder", Map.of("type", "string"),
                            "folder", Map.of("type", "string")));
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            if (params == null) params = Map.of();
            String ref = stringOrNull(params.get("messageRef"));
            if (ref == null) throw new ToolException("missing required 'messageRef'");
            String target = stringOrNull(params.get("targetFolder"));
            if (target == null) throw new ToolException("missing required 'targetFolder'");
            String folder = stringOrNull(params.get("folder"));
            try {
                return resolveClient(ctx).moveMessage(folder, ref, target);
            } catch (RuntimeException ex) {
                throw new ToolException("move_message failed: " + ex.getMessage(), ex);
            }
        }
    }

    private class DeleteMessageTool extends BaseImapTool {
        DeleteMessageTool(String pack, Map<String, Object> raw, Set<String> labels, boolean p, boolean d, String h) {
            super(pack, "delete_message", raw, labels, p, d, h);
        }

        @Override public String description() {
            return "Delete a message. Default: soft-delete (move to pack's trashFolder). "
                    + "Pass hard=true for permanent delete (\\Deleted + EXPUNGE on source — irreversible). "
                    + "Args: messageRef, hard (boolean, default false), folder (optional).";
        }

        @Override public Map<String, Object> paramsSchema() {
            return Map.of(
                    "type", "object",
                    "required", List.of("messageRef"),
                    "properties", Map.of(
                            "messageRef", Map.of("type", "string"),
                            "hard", Map.of("type", "boolean"),
                            "folder", Map.of("type", "string")));
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            if (params == null) params = Map.of();
            String ref = stringOrNull(params.get("messageRef"));
            if (ref == null) throw new ToolException("missing required 'messageRef'");
            boolean hard = boolOrDefault(params.get("hard"), false);
            String folder = stringOrNull(params.get("folder"));
            try {
                return resolveClient(ctx).deleteMessage(folder, ref, hard);
            } catch (RuntimeException ex) {
                throw new ToolException("delete_message failed: " + ex.getMessage(), ex);
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
