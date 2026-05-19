package de.mhus.vance.brain.tools.mail;

import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.mail.SmtpConfig;
import de.mhus.vance.toolpack.mail.SmtpSender;
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
 * {@code smtp_sender} — one sub-tool per pack: {@code <name>__send_message}.
 * SMTP is a single-operation surface (you only ever send), so the pack
 * wraps exactly one tool.
 *
 * <p>Carries the @write + @side-effect labels by default — the message
 * really does leave the building. Conversational recipes (Eddie) push
 * this to the discovery block; only explicit "send an email"-recipes
 * promote it to the primary set.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmtpSenderToolFactory implements ToolFactory {

    public static final String TYPE_ID = "smtp_sender";

    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("host"),
            "properties", Map.of(
                    "host", Map.of("type", "string"),
                    "port", Map.of("type", "integer", "description", "587 (STARTTLS) / 465 (TLS) / 25 (plain)"),
                    "tls", Map.of("type", "boolean", "description", "Implicit-TLS on 465."),
                    "starttls", Map.of("type", "boolean", "description", "STARTTLS upgrade on 587 (default)."),
                    "user", Map.of("type", "string"),
                    "password", Map.of("type", "string"),
                    "from", Map.of("type", "string", "description", "Default From: header.")));

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
        Set<String> labels = labelsFor(document);
        Tool t = new SendMessageTool(
                document.getName(),
                document.getParameters(),
                labels,
                document.isPrimary(),
                document.isDefaultDeferred(),
                document.getPromptHint() == null ? "" : document.getPromptHint());
        List<Tool> out = new ArrayList<>(1);
        out.add(t);
        log.info("SmtpSenderToolFactory pack='{}' tenant='{}' project='{}' produced 1 tool",
                document.getName(), document.getTenantId(), document.getProjectId());
        return out;
    }

    private static Set<String> labelsFor(ServerToolDocument doc) {
        Set<String> out = new LinkedHashSet<>();
        if (doc.getLabels() != null) out.addAll(doc.getLabels());
        out.add("mail");
        out.add("smtp");
        out.add(TYPE_ID + ":" + doc.getName());
        out.add("write");
        out.add("side-effect");
        return Set.copyOf(out);
    }

    // ──────────────────── Tool ────────────────────

    private class SendMessageTool implements Tool {
        private final String name;
        private final Map<String, Object> rawParams;
        private final Set<String> labels;
        private final boolean primary;
        private final boolean deferred;
        private final String promptHint;

        SendMessageTool(String pack, Map<String, Object> raw, Set<String> labels,
                        boolean primary, boolean deferred, String hint) {
            this.name = pack + "__send_message";
            this.rawParams = raw == null ? Map.of() : raw;
            this.labels = labels;
            this.primary = primary;
            this.deferred = deferred;
            this.promptHint = hint;
        }

        @Override public String name() { return name; }
        @Override public String description() {
            return "Send an email via SMTP. Required: to (list of addresses), subject, body. "
                    + "Optional: cc, bcc, html (multipart/alternative when set), from (overrides pack default), replyTo.";
        }
        @Override public boolean primary() { return primary; }
        @Override public boolean deferred() { return deferred; }
        @Override public Set<String> labels() { return labels; }
        @Override public String promptHint() { return promptHint; }

        @Override public Map<String, Object> paramsSchema() {
            return Map.of(
                    "type", "object",
                    "required", List.of("to", "subject", "body"),
                    "properties", Map.of(
                            "to", Map.of("type", "array", "items", Map.of("type", "string")),
                            "cc", Map.of("type", "array", "items", Map.of("type", "string")),
                            "bcc", Map.of("type", "array", "items", Map.of("type", "string")),
                            "subject", Map.of("type", "string"),
                            "body", Map.of("type", "string", "description", "Plain-text body."),
                            "html", Map.of("type", "string",
                                    "description", "Optional HTML alternative — sent as multipart/alternative."),
                            "from", Map.of("type", "string"),
                            "replyTo", Map.of("type", "string")));
        }

        @SuppressWarnings("unchecked")
        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            if (params == null) params = Map.of();
            List<String> to = stringList(params.get("to"));
            if (to.isEmpty()) {
                throw new ToolException("'to' must list at least one recipient");
            }
            List<String> cc = stringList(params.get("cc"));
            List<String> bcc = stringList(params.get("bcc"));
            String subject = stringOrThrow(params.get("subject"), "subject");
            String body = stringOrThrow(params.get("body"), "body");
            String html = stringOrNull(params.get("html"));
            String from = stringOrNull(params.get("from"));
            String replyTo = stringOrNull(params.get("replyTo"));

            // Resolve secret templates at call time against the caller's ctx.
            Map<String, Object> resolved = new LinkedHashMap<>(rawParams.size());
            for (Map.Entry<String, Object> e : rawParams.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String s) {
                    resolved.put(e.getKey(), secretResolver.resolve(s, ctx));
                } else {
                    resolved.put(e.getKey(), v);
                }
            }
            SmtpSender sender = new SmtpSender(SmtpConfig.fromParameters(resolved));
            try {
                return sender.send(new SmtpSender.SendRequest(
                        to, cc.isEmpty() ? null : cc, bcc.isEmpty() ? null : bcc,
                        subject, body, html, from, replyTo));
            } catch (RuntimeException ex) {
                throw new ToolException("send_message failed: " + ex.getMessage(), ex);
            }
        }
    }

    // ─── helpers ───

    @SuppressWarnings("unchecked")
    private static List<String> stringList(@Nullable Object v) {
        if (v == null) return List.of();
        if (v instanceof String s) return s.isBlank() ? List.of() : List.of(s);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) out.add(s);
            }
            return List.copyOf(out);
        }
        return List.of();
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static String stringOrThrow(@Nullable Object v, String field) {
        String s = stringOrNull(v);
        if (s == null) throw new IllegalArgumentException("'" + field + "' is required");
        return s;
    }
}
