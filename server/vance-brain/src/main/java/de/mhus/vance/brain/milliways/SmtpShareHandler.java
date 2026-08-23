package de.mhus.vance.brain.milliways;

import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.tools.mail.SmtpSenderToolFactory;
import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.servertool.ServerToolConfig;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.mail.SmtpConfig;
import de.mhus.vance.toolpack.mail.SmtpSender;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Projects the subject into a mail: the sharer's sentence as the body, the
 * snippet quoted, the link on its own line, and a referenced document as an
 * <b>attachment</b>. Attaching is the point of a mail — a link into a brain
 * the recipient has no account on would be useless — but a subject without a
 * document simply travels in the body.
 *
 * <p>Named after the transport, not the medium. A second way to send mail
 * (a provider API, an outbound queue) has its own configuration, failure
 * modes and availability, so it becomes its own handler rather than an
 * {@code if} in this one.
 *
 * <p><b>No second config surface.</b> The SMTP endpoint is the existing
 * {@code smtp_sender} tool pack ({@link ServerToolConfig}, cascade-resolved
 * project → {@code _vance} → classpath). Sending goes through
 * {@link SmtpSender} rather than around it, so the pack's
 * {@code allowedFrom} / {@code allowedRecipientDomains} guards — configured
 * for the LLM tool — apply here too. An operator who fenced in
 * exfiltration has fenced it in for Milliways as well.
 */
@Component
@Slf4j
public class SmtpShareHandler implements ShareHandler {

    public static final String ID = "smtp";

    static final String FIELD_PACK = "pack";
    static final String FIELD_TO = "to";
    static final String FIELD_SUBJECT = "subject";
    static final String FIELD_TEXT = "text";

    private static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

    private final ServerToolService serverToolService;
    private final SettingsSecretResolver secretResolver;
    private final DocumentService documentService;
    private final long maxAttachmentBytes;

    public SmtpShareHandler(
            ServerToolService serverToolService,
            SettingsSecretResolver secretResolver,
            DocumentService documentService,
            @Value("${vance.milliways.smtp.max-attachment-bytes:10485760}")
            long maxAttachmentBytes) {
        this.serverToolService = serverToolService;
        this.secretResolver = secretResolver;
        this.documentService = documentService;
        this.maxAttachmentBytes = maxAttachmentBytes > 0
                ? maxAttachmentBytes
                : DEFAULT_MAX_ATTACHMENT_BYTES;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<String, String> label() {
        // The transport is in the label, not only in the id: when a second
        // way to send mail exists, the user has to see which one they pick.
        return Map.of("en", "E-Mail (SMTP)", "de", "E-Mail (SMTP)");
    }

    @Override
    public ShareAvailability availability(ShareScope scope) {
        if (packs(scope).isEmpty()) {
            return ShareAvailability.unavailable("No SMTP pack configured in this project");
        }
        return ShareAvailability.ready();
    }

    @Override
    public List<FormFieldDto> form(ShareScope scope) {
        List<ServerToolConfig> packs = packs(scope);
        List<FormFieldDto> fields = new ArrayList<>(4);
        // One pack needs no question. More than one is a real choice —
        // which relay, which From address — so it becomes a field.
        if (packs.size() > 1) {
            List<FormChoiceDto> choices = new ArrayList<>(packs.size());
            for (ServerToolConfig pack : packs) {
                choices.add(FormChoiceDto.builder()
                        .value(pack.name())
                        .label(Map.of("en", packLabel(pack)))
                        .build());
            }
            fields.add(FormFieldDto.builder()
                    .name(FIELD_PACK)
                    .type("select")
                    .label(Map.of("en", "Send via", "de", "Senden über"))
                    .required(true)
                    .defaultValue(packs.get(0).name())
                    .choices(choices)
                    .build());
        }
        fields.add(FormFieldDto.builder()
                .name(FIELD_TO)
                .type("string")
                .label(Map.of("en", "To", "de", "An"))
                .help(Map.of(
                        "en", "Mail addresses, separated by comma.",
                        "de", "Mail-Adressen, durch Komma getrennt."))
                .required(true)
                .build());
        fields.add(FormFieldDto.builder()
                .name(FIELD_SUBJECT)
                .type("string")
                .label(Map.of("en", "Subject", "de", "Betreff"))
                .required(true)
                .defaultValue(scope.displayTitle())
                .build());
        fields.add(FormFieldDto.builder()
                .name(FIELD_TEXT)
                .type("textarea")
                .label(Map.of("en", "Message", "de", "Nachricht"))
                .help(scope.hasDocument()
                        ? Map.of(
                                "en", "The document goes along as an attachment.",
                                "de", "Das Dokument geht als Anhang mit.")
                        : Map.of(
                                "en", "Link and quote go into the message.",
                                "de", "Link und Zitat gehen in die Nachricht."))
                .rows(4)
                .build());
        return List.copyOf(fields);
    }

    @Override
    public ShareResult share(ShareRequest request) {
        ShareScope scope = request.scope();
        ServerToolConfig pack = pickPack(scope, request.string(FIELD_PACK));
        List<String> recipients = parseRecipients(request.string(FIELD_TO));
        if (recipients.isEmpty()) {
            throw new ShareException("Name at least one mail address");
        }
        String subject = request.stringOr(FIELD_SUBJECT, scope.displayTitle());
        String body = bodyOf(scope, request.stringOr(FIELD_TEXT, ""));

        // Attaches only when there is a document. A link-only subject travels
        // in the body — nothing to attach, and nothing lost.
        List<SmtpSender.Attachment> attachments = scope.hasDocument()
                ? List.of(attachmentOf(scope))
                : List.of();

        Map<String, Object> sendResult;
        try {
            // Inside the try, deliberately. SmtpConfig.fromParameters throws
            // IllegalArgumentException for a pack with no host or a non-numeric
            // port, and availability() only checks that the pack exists — so a
            // half-filled smtp_sender reports READY, and building the sender
            // outside meant that refusal escaped as a raw runtime exception:
            // HTTP 500 and outcome=failed, instead of the 422 carrying the one
            // sentence the operator can act on.
            SmtpSender sender = new SmtpSender(SmtpConfig.fromParameters(
                    resolveSecrets(pack.parameters(), scope)));
            sendResult = sender.send(new SmtpSender.SendRequest(
                    recipients,
                    /*cc*/ null,
                    /*bcc*/ null,
                    subject,
                    body,
                    /*html*/ null,
                    /*from*/ null,
                    /*replyTo*/ null,
                    attachments));
        } catch (IllegalArgumentException e) {
            // The pack's own guards (allowedFrom / allowedRecipientDomains)
            // and the missing-From case land here: a refusal the user can
            // act on, not a broken relay.
            throw new ShareException(e.getMessage(), e);
        } catch (SmtpSender.SmtpException e) {
            throw new ShareTransportException(e.getMessage(), e);
        }

        Map<String, Object> details = ShareResult.newDetails();
        details.put("pack", pack.name());
        // Domains, not addresses: the audit trail answers "did something
        // leave the building, and roughly where to" — not "who does this
        // person correspond with".
        details.put("recipientDomains", domainsOf(recipients));
        details.put("recipientCount", recipients.size());
        if (!attachments.isEmpty()) {
            SmtpSender.Attachment attachment = attachments.get(0);
            details.put("attachment", attachment.filename());
            details.put("attachmentBytes", attachment.bytes().length);
        }
        Object messageId = sendResult.get("messageId");
        if (messageId != null) details.put("messageId", messageId);

        log.info("Milliways smtp share: {} recipient(s) via pack '{}', subject={}",
                recipients.size(), pack.name(), scope.subject().parts());
        String message = recipients.size() == 1
                ? "Sent to " + recipients.get(0)
                : "Sent to " + recipients.size() + " recipients";
        return new ShareResult(message, details);
    }

    // ──────────────────── internals ────────────────────

    /** Active {@code smtp_sender} packs in this project's cascade view. */
    private List<ServerToolConfig> packs(ShareScope scope) {
        List<ServerToolConfig> out = new ArrayList<>();
        for (ServerToolConfig config
                : serverToolService.listConfigs(scope.tenantId(), scope.projectId())) {
            if (!SmtpSenderToolFactory.TYPE_ID.equals(config.type())) continue;
            if (!config.enabled()) continue;
            out.add(config);
        }
        return out;
    }

    private ServerToolConfig pickPack(ShareScope scope, @Nullable String requested) {
        List<ServerToolConfig> packs = packs(scope);
        if (packs.isEmpty()) {
            throw new ShareUnavailableException("No SMTP pack configured in this project");
        }
        if (requested == null) return packs.get(0);
        for (ServerToolConfig pack : packs) {
            if (pack.name().equals(requested)) return pack;
        }
        // A name the form never offered — the visible set changed, or the
        // submission was hand-made. Either way not a pack to send through.
        throw new ShareException("Unknown SMTP pack '" + requested + "'");
    }

    /**
     * The pack's parameters with {@code {{secret:…}}} references resolved.
     * Uses the <em>connector</em> path deliberately: a mail relay's
     * password is a {@code PASSWORD} setting, and a connector is allowed to
     * read those — see {@code specification/public/settings-system.md}.
     */
    private Map<String, Object> resolveSecrets(Map<String, Object> parameters, ShareScope scope) {
        ToolInvocationContext ctx = new ToolInvocationContext(
                scope.tenantId(), scope.projectId(), null, null, scope.sharer());
        Map<String, Object> resolved = new LinkedHashMap<>(parameters.size());
        for (Map.Entry<String, Object> e : parameters.entrySet()) {
            Object v = e.getValue();
            resolved.put(e.getKey(), v instanceof String s
                    ? secretResolver.resolveForConnector(s, ctx)
                    : v);
        }
        return resolved;
    }

    /**
     * The document as bytes. No conversion: a {@code kind: canvas} goes out
     * as YAML. That is honest — the recipient gets what is there — and
     * rendering is a separate feature, not a side effect of a menu entry.
     */
    private SmtpSender.Attachment attachmentOf(ShareScope scope) {
        DocumentDocument doc = scope.document();
        if (doc == null) {
            throw new IllegalStateException("attachmentOf called without a document");
        }
        String fileName = scope.fileName();
        // Checked before reading: a named refusal beats a relay timeout
        // forty seconds later. `size` is metadata, so the byte count is
        // checked again below.
        if (doc.getSize() > maxAttachmentBytes) {
            throw new ShareException(tooLarge(doc.getSize()));
        }
        byte[] bytes;
        try (InputStream in = documentService.loadContent(doc)) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ShareTransportException(
                    "Could not read '" + doc.getPath() + "' for sending", e);
        }
        if (bytes.length > maxAttachmentBytes) {
            throw new ShareException(tooLarge(bytes.length));
        }
        String mimeType = doc.getMimeType() == null || doc.getMimeType().isBlank()
                ? "text/markdown"
                : doc.getMimeType();
        return new SmtpSender.Attachment(
                fileName == null ? "document" : fileName, mimeType, bytes);
    }

    private String tooLarge(long actual) {
        return "Too large to send as an attachment: " + (actual / 1024)
                + " KiB, limit is " + (maxAttachmentBytes / 1024) + " KiB";
    }

    /**
     * The sharer's sentence, then the quoted snippet, then the bare link.
     *
     * <p>No provenance footer: the From address already says who sent it, and
     * a project name added by the server would leave the tenant without anyone
     * having chosen to send it.
     *
     * <p>Plain text throughout — no HTML body — so foreign text stays inert.
     * The snippet is quote-marked the conventional way; the link goes on its
     * own line as a bare URL, which every mail client makes clickable without
     * us handing it any markup.
     */
    static String bodyOf(ShareScope scope, String reason) {
        StringBuilder out = new StringBuilder(reason.strip());
        String snippet = scope.subject().snippet();
        if (snippet != null) {
            if (out.length() > 0) out.append("\n\n");
            // Separator between lines rather than after them: trimming a
            // trailing newline afterwards only ever worked because the defang
            // guarantees the snippet has none, and a change there would have
            // turned it into an off-by-one on an empty buffer.
            String[] lines = snippet.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) out.append('\n');
                out.append("> ").append(lines[i]);
            }
        }
        String link = scope.subject().link();
        if (link != null) {
            if (out.length() > 0) out.append("\n\n");
            out.append(link);
        }
        return out.toString();
    }

    private static String packLabel(ServerToolConfig pack) {
        Object from = pack.parameters().get("from");
        if (from instanceof String s && !s.isBlank()) {
            return pack.name() + " (" + s + ")";
        }
        return pack.name();
    }

    /** Splits on comma / semicolon / whitespace; drops blanks and duplicates. */
    private static List<String> parseRecipients(@Nullable String raw) {
        if (raw == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return List.copyOf(out);
    }

    private static List<String> domainsOf(List<String> recipients) {
        Set<String> out = new LinkedHashSet<>();
        for (String r : recipients) {
            int at = r.lastIndexOf('@');
            out.add(at >= 0 && at < r.length() - 1
                    ? r.substring(at + 1).toLowerCase(Locale.ROOT)
                    : "(no domain)");
        }
        return List.copyOf(out);
    }
}
