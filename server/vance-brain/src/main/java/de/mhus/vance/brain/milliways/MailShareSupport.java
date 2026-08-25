package de.mhus.vance.brain.milliways;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.mail.MailMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What every mail-shaped handler does the same way: ask for the same three
 * things, project the subject into the same body, turn a referenced document
 * into the same attachment.
 *
 * <p>Shared rather than copied because the projection <em>is</em> the
 * product decision — the sharer's sentence first, the snippet quoted, the
 * link bare on its own line — and two copies of it would drift into two
 * different mails depending on which transport the sharer picked. What is
 * genuinely transport-specific (which relay, which account, what counts as
 * available) stays in the handler.
 *
 * <p>Not a bean: none of this needs state, and a {@code @Component} would
 * suggest a lifecycle that does not exist.
 */
final class MailShareSupport {

    static final String FIELD_TO = "to";
    static final String FIELD_SUBJECT = "subject";
    static final String FIELD_TEXT = "text";

    private MailShareSupport() {
    }

    /**
     * Recipients, subject, message — in that order, and identical across
     * transports. A handler that needs one more question puts it in front
     * of these (the SMTP handler's relay choice) rather than reshuffling
     * them: the sharer should not have to re-find the To field because
     * they picked a different way out.
     */
    static List<FormFieldDto> mailFields(ShareScope scope) {
        List<FormFieldDto> fields = new ArrayList<>(3);
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
        return fields;
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

    /**
     * The document as bytes. No conversion: a {@code kind: canvas} goes out
     * as YAML. That is honest — the recipient gets what is there — and
     * rendering is a separate feature, not a side effect of a menu entry.
     *
     * @param maxBytes what this transport will carry; the two differ
     *                 (a relay's own limit versus Gmail's), so the cap is a
     *                 parameter rather than a constant
     */
    static MailMessage.Attachment attachmentOf(
            ShareScope scope, DocumentService documentService, long maxBytes) {
        DocumentDocument doc = scope.document();
        if (doc == null) {
            throw new IllegalStateException("attachmentOf called without a document");
        }
        String fileName = scope.fileName();
        // Checked before reading: a named refusal beats a relay timeout
        // forty seconds later. `size` is metadata, so the byte count is
        // checked again below.
        if (doc.getSize() > maxBytes) {
            throw new ShareException(tooLarge(doc.getSize(), maxBytes));
        }
        byte[] bytes;
        try (InputStream in = documentService.loadContent(doc)) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ShareTransportException(
                    "Could not read '" + doc.getPath() + "' for sending", e);
        }
        if (bytes.length > maxBytes) {
            throw new ShareException(tooLarge(bytes.length, maxBytes));
        }
        String mimeType = doc.getMimeType() == null || doc.getMimeType().isBlank()
                ? "text/markdown"
                : doc.getMimeType();
        return new MailMessage.Attachment(
                fileName == null ? "document" : fileName, mimeType, bytes);
    }

    static String tooLarge(long actual, long maxBytes) {
        return "Too large to send as an attachment: " + (actual / 1024)
                + " KiB, limit is " + (maxBytes / 1024) + " KiB";
    }

    /** Splits on comma / semicolon / whitespace; drops blanks and duplicates. */
    static List<String> parseRecipients(@Nullable String raw) {
        if (raw == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return List.copyOf(out);
    }

    /**
     * Domains, not addresses: the audit trail answers "did something leave
     * the building, and roughly where to" — not "who does this person
     * correspond with".
     */
    static List<String> domainsOf(List<String> recipients) {
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
