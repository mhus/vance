package de.mhus.vance.toolpack.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * End-to-end SMTP + IMAP roundtrip against an in-process GreenMail
 * server. Verifies the plumbing: SmtpSender builds + sends a message,
 * ImapClient reads it back. Uses unencrypted ports (faster, simpler,
 * GreenMail's TLS path is fiddly in CI) — the TLS/STARTTLS code path
 * is exercised by the config-level tests + production deployments.
 */
class SmtpImapRoundtripTest {

    @RegisterExtension
    static final GreenMailExtension mail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(com.icegreen.greenmail.configuration.GreenMailConfiguration
                    .aConfig().withUser("alice@example.com", "alice", "secret"));

    @Test
    void send_then_list_then_get_roundtrip() {
        SmtpSender sender = new SmtpSender(SmtpConfig.fromParameters(Map.of(
                "host", "127.0.0.1",
                "port", ServerSetupTest.SMTP.getPort(),
                "tls", false,
                "starttls", false,
                "user", "alice",
                "password", "secret",
                "from", "alice@example.com")));

        Map<String, Object> sendResult = sender.send(new SmtpSender.SendRequest(
                List.of("alice@example.com"),
                /*cc*/ null, /*bcc*/ null,
                "Hello from Vance",
                "This is a plain-text body.\nLine two.",
                /*html*/ null, /*from*/ null, /*replyTo*/ null));

        assertThat(sendResult).containsEntry("subject", "Hello from Vance");
        assertThat((String) sendResult.get("messageId")).isNotBlank();

        // Wait briefly for GreenMail to dispatch.
        GreenMailUtil.sendTextEmailTest(/*to*/ "noop@example.com",
                /*from*/ "x@example.com", "noop", "noop");
        assertThat(mail.waitForIncomingEmail(3000, 1)).isTrue();

        ImapClient imap = new ImapClient(ImapConfig.fromParameters(Map.of(
                "host", "127.0.0.1",
                "port", ServerSetupTest.IMAP.getPort(),
                "tls", false,
                "starttls", false,
                "user", "alice",
                "password", "secret")));

        List<String> folders = imap.listFolders();
        assertThat(folders).contains("INBOX");

        List<Map<String, Object>> msgs = imap.listMessages("INBOX", 10, false, null);
        assertThat(msgs).hasSizeGreaterThanOrEqualTo(1);
        Map<String, Object> first = msgs.get(msgs.size() - 1);   // newest at end
        assertThat((String) first.get("subject")).isEqualTo("Hello from Vance");

        // Fetch full body
        int idx = (int) first.get("messageNumber");
        Map<String, Object> full = imap.getMessage("INBOX", String.valueOf(idx));
        assertThat((String) full.get("body")).contains("plain-text body");
    }

    @Test
    void send_html_multipart_carries_both_parts() {
        SmtpSender sender = new SmtpSender(SmtpConfig.fromParameters(Map.of(
                "host", "127.0.0.1",
                "port", ServerSetupTest.SMTP.getPort(),
                "tls", false,
                "starttls", false,
                "user", "alice",
                "password", "secret",
                "from", "alice@example.com")));

        sender.send(new SmtpSender.SendRequest(
                List.of("alice@example.com"), null, null,
                "Rich mail",
                "Plain version",
                "<p>HTML version</p>",
                null, null));

        assertThat(mail.waitForIncomingEmail(3000, 1)).isTrue();

        ImapClient imap = new ImapClient(ImapConfig.fromParameters(Map.of(
                "host", "127.0.0.1",
                "port", ServerSetupTest.IMAP.getPort(),
                "tls", false,
                "starttls", false,
                "user", "alice",
                "password", "secret")));
        List<Map<String, Object>> msgs = imap.listMessages("INBOX", 10, false, null);
        Map<String, Object> first = msgs.stream()
                .filter(m -> "Rich mail".equals(m.get("subject")))
                .findFirst().orElseThrow();
        Map<String, Object> full = imap.getMessage("INBOX",
                String.valueOf((int) first.get("messageNumber")));
        // Body extraction prefers text/plain — so we get the plain version.
        assertThat((String) full.get("body")).contains("Plain version");
    }
}
