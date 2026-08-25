package de.mhus.vance.toolpack.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * One outgoing mail, and how it renders as RFC 5322 — deliberately with no
 * notion of <em>how</em> it travels.
 *
 * <p>The split exists because there is more than one way out of the house.
 * {@link SmtpSender} hands the assembled message to a relay; the Gmail API
 * takes the same bytes as the request body. Both need identical assembly —
 * the multipart layout, the attachment parts, the UTF-8 headers — and
 * assembling it twice would mean an attachment bug fixed in one transport
 * and not the other.
 *
 * <p>{@code from} is nullable here even though a relay requires one: the
 * Gmail API fills in the authenticated account's address itself, and a
 * From header we invent there is either redundant or rejected as an
 * unregistered send-as alias. A transport that needs the header enforces
 * that itself — see {@link SmtpSender#send}.
 */
public record MailMessage(
        List<String> to,
        @Nullable List<String> cc,
        @Nullable List<String> bcc,
        String subject,
        String body,
        @Nullable String html,
        @Nullable String from,
        @Nullable String replyTo,
        @Nullable List<Attachment> attachments) {

    /**
     * One file to attach. {@code bytes} is held as-is, not copied —
     * callers must not mutate the array afterwards, and a record with an
     * array component has no meaningful {@code equals}; neither matters
     * for a single-shot send payload.
     *
     * <p>{@code mimeType} is nullable on purpose: a caller that does not
     * know the type says so, and {@code application/octet-stream} is filled
     * in at assembly time. The package is {@code @NullMarked}, so leaving it
     * unannotated declared the opposite of what the assembly implements.
     */
    public record Attachment(String filename, @Nullable String mimeType, byte[] bytes) {
    }

    /** Attachment-free form — keeps the pre-attachment call sites intact. */
    public MailMessage(
            List<String> to,
            @Nullable List<String> cc,
            @Nullable List<String> bcc,
            String subject,
            String body,
            @Nullable String html,
            @Nullable String from,
            @Nullable String replyTo) {
        this(to, cc, bcc, subject, body, html, from, replyTo, null);
    }

    /** Never null — the field is nullable, the question "which files" is not. */
    public List<Attachment> attachmentsOrEmpty() {
        return attachments == null ? List.of() : attachments;
    }

    /**
     * Assembles the message in the given session.
     *
     * @param fromAddr the effective From header, or {@code null} to leave it
     *                 to the transport
     */
    public MimeMessage toMimeMessage(Session session, @Nullable String fromAddr)
            throws MessagingException {
        MimeMessage msg = new MimeMessage(session);
        if (fromAddr != null && !fromAddr.isBlank()) {
            msg.setFrom(new InternetAddress(fromAddr));
        }
        if (replyTo != null && !replyTo.isBlank()) {
            msg.setReplyTo(new Address[]{new InternetAddress(replyTo)});
        }
        msg.setRecipients(Message.RecipientType.TO, toAddresses(to));
        if (cc != null && !cc.isEmpty()) {
            msg.setRecipients(Message.RecipientType.CC, toAddresses(cc));
        }
        if (bcc != null && !bcc.isEmpty()) {
            msg.setRecipients(Message.RecipientType.BCC, toAddresses(bcc));
        }
        msg.setSubject(subject, "UTF-8");

        List<Attachment> files = attachmentsOrEmpty();
        if (!files.isEmpty()) {
            // multipart/mixed with the whole body (plain, or the
            // alternative pair) as the first part — nesting the
            // alternative inside the mixed keeps the plain/HTML choice
            // intact instead of flattening it next to the files.
            MimeMultipart mixed = new MimeMultipart("mixed");
            MimeBodyPart bodyPart = new MimeBodyPart();
            if (html != null && !html.isBlank()) {
                bodyPart.setContent(alternativeBody());
            } else {
                bodyPart.setText(body, "UTF-8");
            }
            mixed.addBodyPart(bodyPart);
            for (Attachment a : files) {
                mixed.addBodyPart(attachmentPart(a));
            }
            msg.setContent(mixed);
        } else if (html != null && !html.isBlank()) {
            msg.setContent(alternativeBody());
        } else {
            msg.setText(body, "UTF-8");
        }
        msg.saveChanges();
        return msg;
    }

    /**
     * The assembled message as RFC-5322 bytes, for transports that carry the
     * whole thing as a payload rather than speaking SMTP.
     *
     * <p>Uses a bare session: none of the transport properties matter for
     * serialisation, and building one keeps this callable without a relay
     * configuration in sight.
     */
    public byte[] toRfc822Bytes(@Nullable String fromAddr)
            throws MessagingException, IOException {
        MimeMessage msg = toMimeMessage(Session.getInstance(new Properties()), fromAddr);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        msg.writeTo(out);
        return out.toByteArray();
    }

    // ──────────────────── internals ────────────────────

    /**
     * multipart/alternative — plain first, HTML second is the canonical
     * layout. Some clients pick the LAST part, hence HTML last so it wins
     * for rich-capable clients.
     */
    private MimeMultipart alternativeBody() throws MessagingException {
        MimeMultipart mp = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText(body, "UTF-8");
        mp.addBodyPart(text);
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html, "text/html; charset=UTF-8");
        mp.addBodyPart(htmlPart);
        return mp;
    }

    private static MimeBodyPart attachmentPart(Attachment a) throws MessagingException {
        // Blank, not null: the package is @NullMarked, so a null filename or
        // null bytes is a contract violation the caller has to fix, while an
        // empty name is a value a form can produce.
        if (a.filename().isBlank()) {
            throw new IllegalArgumentException("attachment: 'filename' is required");
        }
        String mimeType = a.mimeType() == null || a.mimeType().isBlank()
                ? "application/octet-stream"
                : a.mimeType();
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(a.bytes(), mimeType)));
        part.setFileName(a.filename());
        part.setDisposition(Part.ATTACHMENT);
        return part;
    }

    private static Address[] toAddresses(Collection<String> raw) throws AddressException {
        List<Address> out = new ArrayList<>(raw.size());
        for (String r : raw) {
            if (r == null || r.isBlank()) continue;
            out.add(new InternetAddress(r.trim()));
        }
        if (out.isEmpty()) {
            throw new AddressException("no recipients after trimming");
        }
        return out.toArray(new Address[0]);
    }
}
