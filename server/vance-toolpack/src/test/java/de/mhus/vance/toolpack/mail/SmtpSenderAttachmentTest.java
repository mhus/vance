package de.mhus.vance.toolpack.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Attachment path of {@link SmtpSender}, against an in-process GreenMail
 * server. The interesting properties are structural: the body must stay
 * the first part, the file must arrive with its name and bytes intact, and
 * a plain/HTML pair must survive <em>nested</em> inside the mixed multipart
 * rather than flattened next to the attachment.
 *
 * <p>Binds a <b>dynamic</b> port rather than {@code ServerSetupTest.SMTP}:
 * that constant is a fixed 3025, which {@link SmtpImapRoundtripTest}
 * already claims, and two GreenMail extensions in one surefire JVM
 * overlap far enough that the second cannot bind.
 */
class SmtpSenderAttachmentTest {

    @RegisterExtension
    static final GreenMailExtension mail = new GreenMailExtension(
            new ServerSetup(0, null, ServerSetup.PROTOCOL_SMTP))
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("alice@example.com", "alice", "secret"));

    private static SmtpSender sender() {
        return new SmtpSender(SmtpConfig.fromParameters(Map.of(
                "host", "127.0.0.1",
                "port", mail.getSmtp().getPort(),
                "tls", false,
                "starttls", false,
                "user", "alice",
                "password", "secret",
                "from", "alice@example.com")));
    }

    @Test
    void send_withAttachment_deliversFileNameAndContent() throws Exception {
        String text = "# Results\n\nThe canyon test is done.\n";

        Map<String, Object> result = sender().send(new MailMessage(
                List.of("alice@example.com"), null, null,
                "Results", "Look at this.", null, null, null,
                List.of(new MailMessage.Attachment(
                        "results.md", "text/markdown", text.getBytes(StandardCharsets.UTF_8)))));

        assertThat(result).containsEntry("attachments", List.of("results.md"));
        assertThat(mail.waitForIncomingEmail(3000, 1)).isTrue();

        MimeMessage received = mail.getReceivedMessages()[0];
        assertThat(received.getContentType()).contains("multipart/mixed");
        Multipart mixed = (Multipart) received.getContent();
        assertThat(mixed.getCount()).isEqualTo(2);

        // First part is the body — an attachment must never displace it.
        assertThat((String) mixed.getBodyPart(0).getContent()).isEqualTo("Look at this.");

        Part file = mixed.getBodyPart(1);
        assertThat(file.getFileName()).isEqualTo("results.md");
        assertThat(file.getDisposition()).isEqualToIgnoringCase(Part.ATTACHMENT);
        assertThat(file.getContentType()).contains("text/markdown");
        // A text/* part travels in canonical MIME form, so LF arrives as
        // CRLF. Content is preserved, byte-identity is not — asserting the
        // latter for text would be asserting a thing that is not true.
        String delivered = new String(
                file.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(delivered.replace("\r\n", "\n")).isEqualTo(text);
    }

    @Test
    void send_binaryAttachment_isByteIdentical() throws Exception {
        byte[] payload = {0, 1, 2, (byte) 0xFF, '\n', '\r', 0x7F};

        sender().send(new MailMessage(
                List.of("alice@example.com"), null, null,
                "Binary", "Attached.", null, null, null,
                List.of(new MailMessage.Attachment("blob.bin", "application/octet-stream", payload))));

        assertThat(mail.waitForIncomingEmail(3000, 1)).isTrue();
        Multipart mixed = (Multipart) mail.getReceivedMessages()[0].getContent();
        Part file = mixed.getBodyPart(1);
        // Non-text is base64-encoded in transit and therefore exact —
        // including the bytes that would be rewritten in a text part.
        assertThat(file.getInputStream().readAllBytes()).isEqualTo(payload);
    }

    @Test
    void send_withAttachmentAndHtml_keepsAlternativeNested() throws Exception {
        sender().send(new MailMessage(
                List.of("alice@example.com"), null, null,
                "Rich", "Plain version", "<p>HTML version</p>", null, null,
                List.of(new MailMessage.Attachment("a.txt", "text/plain",
                        "x".getBytes(StandardCharsets.UTF_8)))));

        assertThat(mail.waitForIncomingEmail(3000, 1)).isTrue();
        MimeMessage received = mail.getReceivedMessages()[0];
        Multipart mixed = (Multipart) received.getContent();

        // The plain/HTML choice is one part inside the mixed, not two parts
        // sitting beside the file — flattening it would make clients treat
        // the plain text as an extra body.
        assertThat(mixed.getCount()).isEqualTo(2);
        assertThat(mixed.getBodyPart(0).getContentType()).contains("multipart/alternative");
        Multipart alternative = (Multipart) mixed.getBodyPart(0).getContent();
        assertThat(alternative.getCount()).isEqualTo(2);
        assertThat(mixed.getBodyPart(1).getFileName()).isEqualTo("a.txt");
    }

    @Test
    void send_multipleAttachments_allArriveInOrder() throws Exception {
        sender().send(new MailMessage(
                List.of("alice@example.com"), null, null,
                "Two files", "Both attached.", null, null, null,
                List.of(
                        new MailMessage.Attachment("one.txt", "text/plain",
                                "1".getBytes(StandardCharsets.UTF_8)),
                        new MailMessage.Attachment("two.bin", null, new byte[]{0, 1, 2}))));

        assertThat(mail.waitForIncomingEmail(3000, 1)).isTrue();
        Multipart mixed = (Multipart) mail.getReceivedMessages()[0].getContent();
        List<String> names = new ArrayList<>();
        for (int i = 1; i < mixed.getCount(); i++) {
            names.add(mixed.getBodyPart(i).getFileName());
        }
        assertThat(names).containsExactly("one.txt", "two.bin");
        // A null mimeType must not produce a broken header.
        assertThat(mixed.getBodyPart(2).getContentType()).contains("application/octet-stream");
    }

    @Test
    void send_withoutAttachments_staysPlainText() throws Exception {
        sender().send(new MailMessage(
                List.of("alice@example.com"), null, null,
                "Plain", "No files here.", null, null, null));

        assertThat(mail.waitForIncomingEmail(3000, 1)).isTrue();
        MimeMessage received = mail.getReceivedMessages()[0];
        assertThat(received.getContentType()).contains("text/plain");
        assertThat((String) received.getContent()).isEqualTo("No files here.");
    }
}
