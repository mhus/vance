package de.mhus.vance.brain.milliways;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentRef;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.mail.MailMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The projection both mail handlers share. Tested here rather than through
 * one of them, because that is the point of it existing: SMTP and Gmail must
 * produce the same body, ask the same three questions, and cut the same
 * attachment — a test that only ran through one transport would not notice
 * the other drifting.
 */
class MailShareSupportTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String PATH = "notes/results.md";
    private static final SecurityContext MARA =
            SecurityContext.user("mara", TENANT, List.of());
    private static final byte[] CONTENT = "# Results\n".getBytes(StandardCharsets.UTF_8);

    // ── The body projection ────────────────────────────────────────

    @Test
    void bodyOf_reasonOnly_isJustTheReason() {
        assertThat(MailShareSupport.bodyOf(documentScope(), "  have a look  "))
                .isEqualTo("have a look");
    }

    @Test
    void bodyOf_reasonAndSnippet_quotesTheSnippetBelowTheReason() {
        ShareScope scope = scopeWith(
                new ShareSubject(null, null, "line one\nline two", null));

        assertThat(MailShareSupport.bodyOf(scope, "have a look"))
                .isEqualTo("have a look\n\n> line one\n> line two");
    }

    @Test
    void bodyOf_linkOnly_isTheBareUrl() {
        ShareScope scope = scopeWith(
                new ShareSubject(null, "https://example.com/hit", null, null));

        // A bare URL on its own line: every mail client makes that clickable
        // without us handing it any markup.
        assertThat(MailShareSupport.bodyOf(scope, "")).isEqualTo("https://example.com/hit");
    }

    @Test
    void bodyOf_everything_keepsReasonSnippetLinkInThatOrder() {
        ShareScope scope = scopeWith(new ShareSubject(
                null, "https://example.com/hit", "the quote", null));

        assertThat(MailShareSupport.bodyOf(scope, "have a look"))
                .isEqualTo("have a look\n\n> the quote\n\nhttps://example.com/hit");
    }

    @Test
    void bodyOf_snippetOnlyWithoutReason_startsWithTheQuote() {
        // The empty-reason case is the one that used to sit one character away
        // from a StringIndexOutOfBounds on an empty buffer.
        ShareScope scope = scopeWith(new ShareSubject(null, null, "the quote", null));

        assertThat(MailShareSupport.bodyOf(scope, "")).isEqualTo("> the quote");
    }

    // ── The form ───────────────────────────────────────────────────

    @Test
    void mailFields_askRecipientsSubjectMessageInThatOrder() {
        assertThat(MailShareSupport.mailFields(documentScope()))
                .extracting(FormFieldDto::getName)
                .containsExactly(
                        MailShareSupport.FIELD_TO,
                        MailShareSupport.FIELD_SUBJECT,
                        MailShareSupport.FIELD_TEXT);
    }

    @Test
    void mailFields_documentlessSubject_promisesNoAttachment() {
        List<FormFieldDto> fields = MailShareSupport.mailFields(scopeWith(
                new ShareSubject("Canyon test results", "https://example.com/hit", null, null)));

        assertThat(fields.get(1).getDefaultValue()).isEqualTo("Canyon test results");
        assertThat(fields.get(2).getHelp().values())
                .noneMatch(h -> h.toLowerCase(Locale.ROOT).contains("attach"));
    }

    // ── The attachment ─────────────────────────────────────────────

    @Test
    void attachmentOf_carriesTheDocumentBytesUnderItsFileName() {
        DocumentService documents = mock(DocumentService.class);
        when(documents.loadContent(any(DocumentDocument.class)))
                .thenAnswer(inv -> new ByteArrayInputStream(CONTENT));

        MailMessage.Attachment attachment =
                MailShareSupport.attachmentOf(documentScope(), documents, 1024);

        assertThat(attachment.filename()).isEqualTo("results.md");
        assertThat(attachment.mimeType()).isEqualTo("text/markdown");
        assertThat(attachment.bytes()).isEqualTo(CONTENT);
    }

    @Test
    void attachmentOf_beyondTheCap_isRefusedBeforeReading() {
        DocumentService documents = mock(DocumentService.class);

        assertThatThrownBy(() -> MailShareSupport.attachmentOf(
                scopeOf(doc("Results", 5000)), documents, 1024))
                .isInstanceOf(ShareException.class)
                .hasMessageContaining("limit");

        // Never even opened the stream — a named refusal, not a slow one.
        verify(documents, never()).loadContent(any(DocumentDocument.class));
    }

    @Test
    void attachmentOf_capIsPerTransport_soTheSameDocumentCanPassOne() {
        DocumentService documents = mock(DocumentService.class);
        when(documents.loadContent(any(DocumentDocument.class)))
                .thenAnswer(inv -> new ByteArrayInputStream(CONTENT));
        ShareScope scope = scopeOf(doc("Results", 4096));

        assertThatThrownBy(() -> MailShareSupport.attachmentOf(scope, documents, 1024))
                .isInstanceOf(ShareException.class);
        assertThat(MailShareSupport.attachmentOf(scope, documents, 8192).bytes())
                .isEqualTo(CONTENT);
    }

    // ── Recipients ─────────────────────────────────────────────────

    @Test
    void parseRecipients_splitsOnSeparatorsAndDropsDuplicates() {
        assertThat(MailShareSupport.parseRecipients(
                " ford@example.com, zaphod@example.org; ford@example.com "))
                .containsExactly("ford@example.com", "zaphod@example.org");
    }

    @Test
    void parseRecipients_blank_isEmpty() {
        assertThat(MailShareSupport.parseRecipients("   ")).isEmpty();
        assertThat(MailShareSupport.parseRecipients(null)).isEmpty();
    }

    @Test
    void domainsOf_recordsDomainsNotAddresses() {
        // The audit trail answers "roughly where to", not "who does this
        // person correspond with".
        assertThat(MailShareSupport.domainsOf(List.of(
                "ford@Example.com", "zaphod@example.com", "trillian@heart.test")))
                .containsExactly("example.com", "heart.test");
    }

    @Test
    void domainsOf_addressWithoutDomain_isNamedNotDropped() {
        assertThat(MailShareSupport.domainsOf(List.of("postmaster")))
                .containsExactly("(no domain)");
    }

    // ── helpers ────────────────────────────────────────────────────

    private static ShareScope documentScope() {
        return scopeOf(doc("Results", CONTENT.length));
    }

    private static ShareScope scopeOf(DocumentDocument document) {
        return new ShareScope(
                MARA, TENANT, PROJECT,
                ShareSubject.ofDocument(DocumentRef.of(PROJECT, PATH)),
                document);
    }

    /** A document-less scope carrying exactly the given subject. */
    private static ShareScope scopeWith(ShareSubject subject) {
        return new ShareScope(MARA, TENANT, PROJECT, subject, null);
    }

    private static DocumentDocument doc(String title, long size) {
        return DocumentDocument.builder()
                .id("doc-1")
                .tenantId(TENANT)
                .projectId(PROJECT)
                .path(PATH)
                .title(title)
                .mimeType("text/markdown")
                .size(size)
                .build();
    }
}
