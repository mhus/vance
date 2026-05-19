package de.mhus.vance.toolpack.mail;

import jakarta.mail.Address;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Thin Jakarta-Mail wrapper around a single IMAP store. Builds a fresh
 * {@link Store} per operation — IMAP servers are cheap to reconnect to
 * and connection lifetime across LLM turns is a hassle (timeouts,
 * folder-state, server-side session limits). Pool can be added later
 * if profiling shows it's worth it.
 *
 * <p>Pure read surface in v1: {@link #listFolders}, {@link #listMessages},
 * {@link #getMessage}. Mark/move/delete come in v2 once the security
 * model around AI-driven mailbox mutation is settled.
 */
@Slf4j
public final class ImapClient {

    private final ImapConfig config;

    public ImapClient(ImapConfig config) {
        this.config = config;
    }

    /**
     * Top-level folder names visible to the configured user. Hidden /
     * system folders (Trash, Spam, …) are returned verbatim — let the
     * caller filter if needed.
     */
    public List<String> listFolders() {
        return withStore(store -> {
            Folder root = store.getDefaultFolder();
            Folder[] folders = root.list("*");
            List<String> names = new ArrayList<>(folders.length);
            for (Folder f : folders) {
                names.add(f.getFullName());
            }
            return names;
        });
    }

    /**
     * Header summaries (no body) for messages in {@code folderName}.
     * {@code limit} caps the result; {@code unreadOnly} filters to
     * unseen-only; {@code since} restricts to messages received at or
     * after the given instant.
     */
    public List<Map<String, Object>> listMessages(
            @Nullable String folderName,
            int limit,
            boolean unreadOnly,
            @Nullable Instant since) {
        String fname = folderName == null || folderName.isBlank()
                ? config.defaultFolder() : folderName;
        int cap = Math.max(1, Math.min(limit, 500));
        return withStore(store -> {
            Folder folder = store.getFolder(fname);
            if (!folder.exists()) {
                throw new IllegalArgumentException("Folder not found: " + fname);
            }
            folder.open(Folder.READ_ONLY);
            try {
                SearchTerm term = buildSearchTerm(unreadOnly, since);
                Message[] msgs = term == null ? folder.getMessages() : folder.search(term);

                // Newest first — IMAP returns ascending by default.
                Message[] window = pickLast(msgs, cap);

                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(FetchProfile.Item.FLAGS);
                folder.fetch(window, fp);

                List<Map<String, Object>> out = new ArrayList<>(window.length);
                for (Message m : window) {
                    out.add(headerSummary(m));
                }
                return out;
            } finally {
                try { folder.close(false); } catch (MessagingException ignored) { /* swallow */ }
            }
        });
    }

    /**
     * Full envelope + body for one message, identified by 1-based folder
     * index OR by Message-ID header (when the value starts with
     * {@code <} or is non-numeric).
     */
    public Map<String, Object> getMessage(@Nullable String folderName, String messageRef) {
        if (messageRef == null || messageRef.isBlank()) {
            throw new IllegalArgumentException("messageRef is required");
        }
        String fname = folderName == null || folderName.isBlank()
                ? config.defaultFolder() : folderName;
        return withStore(store -> {
            Folder folder = store.getFolder(fname);
            if (!folder.exists()) {
                throw new IllegalArgumentException("Folder not found: " + fname);
            }
            folder.open(Folder.READ_ONLY);
            try {
                Message hit = resolveMessage(folder, messageRef);
                if (hit == null) {
                    throw new IllegalArgumentException("Message not found: " + messageRef);
                }
                Map<String, Object> out = new LinkedHashMap<>(headerSummary(hit));
                out.put("body", extractBody(hit));
                return out;
            } finally {
                try { folder.close(false); } catch (MessagingException ignored) { /* swallow */ }
            }
        });
    }

    // ──────────────────── Internals ────────────────────

    /** Resolve a message by numeric folder index (1-based) or by Message-ID. */
    private static @Nullable Message resolveMessage(Folder folder, String ref) throws MessagingException {
        // Numeric → 1-based folder index.
        try {
            int idx = Integer.parseInt(ref.trim());
            int count = folder.getMessageCount();
            if (idx < 1 || idx > count) return null;
            return folder.getMessage(idx);
        } catch (NumberFormatException ignored) {
            // fall through to Message-ID lookup
        }
        String needle = ref.trim();
        for (Message m : folder.getMessages()) {
            String[] ids = m.getHeader("Message-ID");
            if (ids == null) continue;
            for (String id : ids) {
                if (id != null && id.equals(needle)) return m;
            }
        }
        return null;
    }

    private static Map<String, Object> headerSummary(Message m) throws MessagingException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("messageNumber", m.getMessageNumber());
        String[] ids = m.getHeader("Message-ID");
        row.put("messageId", ids != null && ids.length > 0 ? ids[0] : null);
        row.put("subject", m.getSubject());
        row.put("from", joinAddresses(m.getFrom()));
        row.put("to", joinAddresses(m.getRecipients(Message.RecipientType.TO)));
        Address[] cc = m.getRecipients(Message.RecipientType.CC);
        if (cc != null && cc.length > 0) row.put("cc", joinAddresses(cc));
        Date sent = m.getSentDate();
        if (sent != null) row.put("sentAt", sent.toInstant().toString());
        Date received = m.getReceivedDate();
        if (received != null) row.put("receivedAt", received.toInstant().toString());
        row.put("seen", m.isSet(Flags.Flag.SEEN));
        return row;
    }

    private static String joinAddresses(@Nullable Address[] addrs) {
        if (addrs == null || addrs.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addrs.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(addrs[i].toString());
        }
        return sb.toString();
    }

    /**
     * Best-effort body extraction. For multipart messages, the first
     * {@code text/plain} part wins; falls back to the first {@code text/html}.
     * Returns an empty string when no text part is present (e.g. pure
     * attachment messages).
     */
    private static String extractBody(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content == null ? "" : content.toString();
        }
        if (part.isMimeType("multipart/*")) {
            MimeMultipart mp = (MimeMultipart) part.getContent();
            // Two passes: prefer text/plain, then text/html.
            for (int i = 0; i < mp.getCount(); i++) {
                Part sub = mp.getBodyPart(i);
                if (sub.isMimeType("text/plain")) return extractBody(sub);
            }
            for (int i = 0; i < mp.getCount(); i++) {
                Part sub = mp.getBodyPart(i);
                if (sub.isMimeType("text/html")) return extractBody(sub);
            }
        }
        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            return content == null ? "" : content.toString();
        }
        return "";
    }

    private static Message[] pickLast(Message[] msgs, int cap) {
        if (msgs.length <= cap) return msgs;
        return Arrays.copyOfRange(msgs, msgs.length - cap, msgs.length);
    }

    private static @Nullable SearchTerm buildSearchTerm(boolean unreadOnly, @Nullable Instant since) {
        SearchTerm unread = unreadOnly ? new FlagTerm(new Flags(Flags.Flag.SEEN), false) : null;
        SearchTerm dateTerm = since != null
                ? new ReceivedDateTerm(jakarta.mail.search.ComparisonTerm.GE, Date.from(since))
                : null;
        if (unread == null) return dateTerm;
        if (dateTerm == null) return unread;
        return new AndTerm(unread, dateTerm);
    }

    @FunctionalInterface
    private interface StoreOp<T> {
        T apply(Store store) throws MessagingException, IOException;
    }

    private <T> T withStore(StoreOp<T> op) {
        Session session = Session.getInstance(buildProperties());
        try (Store store = session.getStore(config.protocol())) {
            store.connect(config.host(), config.port(), config.user(), config.password());
            try {
                return op.apply(store);
            } catch (MessagingException | IOException e) {
                throw new ImapException(
                        "IMAP operation failed on " + config.host() + ": " + e.getMessage(), e);
            }
        } catch (MessagingException e) {
            throw new ImapException(
                    "IMAP connect/close failed on " + config.host()
                            + " port " + config.port() + " protocol " + config.protocol()
                            + ": " + e.getMessage(), e);
        }
    }

    private Properties buildProperties() {
        Properties p = new Properties();
        String protocol = config.protocol();
        String prefix = "mail." + protocol + ".";
        p.put(prefix + "host", config.host());
        p.put(prefix + "port", String.valueOf(config.port()));
        p.put(prefix + "connectiontimeout", String.valueOf(config.timeoutSeconds() * 1000L));
        p.put(prefix + "timeout", String.valueOf(config.timeoutSeconds() * 1000L));
        p.put(prefix + "writetimeout", String.valueOf(config.timeoutSeconds() * 1000L));
        if (config.starttls()) {
            p.put("mail.imap.starttls.enable", "true");
            p.put("mail.imap.starttls.required", "true");
        }
        return p;
    }

    /** Wrapper so callers don't have to import jakarta.mail directly. */
    public static class ImapException extends RuntimeException {
        public ImapException(String msg, Throwable cause) { super(msg, cause); }
    }
}
