package de.mhus.vance.toolpack.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Address;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Single-shot SMTP sender using Jakarta Mail. Each
 * {@link #send(SendRequest) send} call opens a fresh transport — SMTP
 * servers handle this fine and we avoid sticky-session bugs across
 * LLM turns.
 *
 * <p>Plain-text only path: {@code body} is the message body.
 * Multipart path: pass {@code html != null} to send a multipart/
 * alternative with both plain and HTML versions (the canonical "rich
 * email with plain fallback" recipe).
 *
 * <p>With {@code attachments} the message becomes a multipart/mixed
 * whose first part is that body — plain or the alternative pair, nested
 * rather than flattened, so the plain/HTML choice survives.
 */
@Slf4j
public final class SmtpSender {

    private final SmtpConfig config;

    public SmtpSender(SmtpConfig config) {
        this.config = config;
    }

    /**
     * One file to attach. {@code bytes} is held as-is, not copied —
     * callers must not mutate the array afterwards, and a record with an
     * array component has no meaningful {@code equals}; neither matters
     * for a single-shot send payload.
     *
     * <p>{@code mimeType} is nullable on purpose: a caller that does not
     * know the type says so, and {@code application/octet-stream} is filled
     * in at send time. The package is {@code @NullMarked}, so leaving it
     * unannotated declared the opposite of what the sender implements.
     */
    public record Attachment(String filename, @Nullable String mimeType, byte[] bytes) {
    }

    /** Payload for one outgoing message. */
    public record SendRequest(
            List<String> to,
            @Nullable List<String> cc,
            @Nullable List<String> bcc,
            String subject,
            String body,
            @Nullable String html,
            @Nullable String from,
            @Nullable String replyTo,
            @Nullable List<Attachment> attachments) {

        /** Attachment-free form — keeps the pre-attachment call sites intact. */
        public SendRequest(
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
    }

    /**
     * Sends the message. Returns a small summary map ({@code messageId},
     * {@code recipients}, etc.) — useful for the LLM tool result.
     *
     * @throws SmtpException on transport failure (auth, network, refused).
     */
    public Map<String, Object> send(SendRequest req) {
        if (req == null) throw new IllegalArgumentException("SendRequest is required");
        if (req.to() == null || req.to().isEmpty()) {
            throw new IllegalArgumentException("send_message: 'to' must list at least one recipient");
        }
        if (req.subject() == null) {
            throw new IllegalArgumentException("send_message: 'subject' is required");
        }
        if (req.body() == null) {
            throw new IllegalArgumentException("send_message: 'body' is required");
        }

        Session session = Session.getInstance(buildProperties(), authenticator());
        try {
            MimeMessage msg = new MimeMessage(session);
            String fromAddr = pick(req.from(), config.from());
            if (fromAddr == null || fromAddr.isBlank()) {
                throw new IllegalArgumentException(
                        "send_message: no 'from' address — set parameters.from on the pack "
                                + "or pass from=... on the call");
            }
            // Abuse guards (defense-in-depth vs. prompt-injected send_message):
            // an allowedFrom list blocks sender spoofing through the org relay;
            // an allowedRecipientDomains list blocks data exfiltration to an
            // attacker address. Empty lists = unrestricted (backward compatible).
            enforceFromPolicy(fromAddr);
            enforceRecipientPolicy(req.to());
            enforceRecipientPolicy(req.cc());
            enforceRecipientPolicy(req.bcc());
            msg.setFrom(new InternetAddress(fromAddr));
            if (req.replyTo() != null && !req.replyTo().isBlank()) {
                msg.setReplyTo(new Address[]{new InternetAddress(req.replyTo())});
            }
            msg.setRecipients(Message.RecipientType.TO, toAddresses(req.to()));
            if (req.cc() != null && !req.cc().isEmpty()) {
                msg.setRecipients(Message.RecipientType.CC, toAddresses(req.cc()));
            }
            if (req.bcc() != null && !req.bcc().isEmpty()) {
                msg.setRecipients(Message.RecipientType.BCC, toAddresses(req.bcc()));
            }
            msg.setSubject(req.subject(), "UTF-8");

            List<Attachment> attachments = req.attachments() == null
                    ? List.of()
                    : req.attachments();
            if (!attachments.isEmpty()) {
                // multipart/mixed with the whole body (plain, or the
                // alternative pair) as the first part — nesting the
                // alternative inside the mixed keeps the plain/HTML choice
                // intact instead of flattening it next to the files.
                MimeMultipart mixed = new MimeMultipart("mixed");
                MimeBodyPart bodyPart = new MimeBodyPart();
                if (req.html() != null && !req.html().isBlank()) {
                    bodyPart.setContent(alternativeBody(req));
                } else {
                    bodyPart.setText(req.body(), "UTF-8");
                }
                mixed.addBodyPart(bodyPart);
                for (Attachment a : attachments) {
                    mixed.addBodyPart(attachmentPart(a));
                }
                msg.setContent(mixed);
            } else if (req.html() != null && !req.html().isBlank()) {
                msg.setContent(alternativeBody(req));
            } else {
                msg.setText(req.body(), "UTF-8");
            }
            msg.saveChanges();
            Transport.send(msg);

            String messageId = msg.getMessageID();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("messageId", messageId);
            out.put("from", fromAddr);
            out.put("to", req.to());
            if (req.cc() != null) out.put("cc", req.cc());
            if (req.bcc() != null) out.put("bcc", req.bcc());
            out.put("subject", req.subject());
            if (!attachments.isEmpty()) {
                out.put("attachments", attachments.stream().map(Attachment::filename).toList());
            }
            return out;
        } catch (MessagingException e) {
            throw new SmtpException(
                    "SMTP send failed via " + config.host() + ":" + config.port()
                            + " — " + e.getMessage(), e);
        }
    }

    // ──────────────────── Internals ────────────────────

    /**
     * multipart/alternative — plain first, HTML second is the canonical
     * layout. Some clients pick the LAST part, hence HTML last so it wins
     * for rich-capable clients.
     */
    private static MimeMultipart alternativeBody(SendRequest req) throws MessagingException {
        MimeMultipart mp = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText(req.body(), "UTF-8");
        mp.addBodyPart(text);
        MimeBodyPart html = new MimeBodyPart();
        html.setContent(req.html(), "text/html; charset=UTF-8");
        mp.addBodyPart(html);
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

    private Address[] toAddresses(Collection<String> raw) throws AddressException {
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

    private @Nullable Authenticator authenticator() {
        if (config.user().isEmpty() && config.password().isEmpty()) return null;
        return new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.user(), config.password());
            }
        };
    }

    private Properties buildProperties() {
        Properties p = new Properties();
        String protocol = config.protocol();
        p.put("mail.transport.protocol", protocol);
        String prefix = "mail." + protocol + ".";
        p.put(prefix + "host", config.host());
        p.put(prefix + "port", String.valueOf(config.port()));
        p.put(prefix + "connectiontimeout", String.valueOf(config.timeoutSeconds() * 1000L));
        p.put(prefix + "timeout", String.valueOf(config.timeoutSeconds() * 1000L));
        p.put(prefix + "writetimeout", String.valueOf(config.timeoutSeconds() * 1000L));
        if (config.starttls() && !config.tls()) {
            p.put(prefix + "starttls.enable", "true");
            // Require, not just enable: without this a MITM stripping the
            // server's STARTTLS advertisement makes Jakarta Mail fall back to
            // cleartext, leaking AUTH credentials + message bodies.
            p.put(prefix + "starttls.required", "true");
        }
        if (!config.user().isEmpty()) {
            p.put(prefix + "auth", "true");
        }
        return p;
    }

    private static @Nullable String pick(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) return a;
        return b == null || b.isBlank() ? null : b;
    }

    private void enforceFromPolicy(String fromAddr) {
        java.util.List<String> allowed = config.allowedFrom();
        if (allowed.isEmpty()) return;
        String f = fromAddr.trim().toLowerCase(java.util.Locale.ROOT);
        boolean ok = allowed.stream().anyMatch(a -> a.trim().toLowerCase(java.util.Locale.ROOT).equals(f));
        if (!ok) {
            throw new IllegalArgumentException(
                    "send_message: From '" + fromAddr + "' is not in the allowed sender list");
        }
    }

    private void enforceRecipientPolicy(@Nullable Collection<String> recipients) {
        java.util.List<String> allowed = config.allowedRecipientDomains();
        if (allowed.isEmpty() || recipients == null) return;
        for (String r : recipients) {
            if (r == null || r.isBlank()) continue;
            int at = r.lastIndexOf('@');
            String domain = at >= 0 ? r.substring(at + 1).trim().toLowerCase(java.util.Locale.ROOT) : "";
            boolean ok = allowed.stream()
                    .anyMatch(d -> d.trim().toLowerCase(java.util.Locale.ROOT).equals(domain));
            if (!ok) {
                throw new IllegalArgumentException(
                        "send_message: recipient '" + r + "' is not in an allowed domain");
            }
        }
    }

    /** Wrapper exception so callers don't have to import jakarta.mail. */
    public static class SmtpException extends RuntimeException {
        public SmtpException(String msg, Throwable cause) { super(msg, cause); }
    }
}
