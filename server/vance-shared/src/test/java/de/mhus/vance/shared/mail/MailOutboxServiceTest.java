package de.mhus.vance.shared.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.crypto.AesEncryptionService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * The state transitions of {@link MailOutboxService} against mocks. The atomic
 * claim under concurrent senders is Mongo's job; here we check what the
 * service does with the document it gets back.
 */
class MailOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private MailOutboxRepository repository;
    private MongoTemplate mongoTemplate;
    private AesEncryptionService encryption;
    private MailOutboxService service;

    @BeforeEach
    void setUp() {
        repository = mock(MailOutboxRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        encryption = mock(AesEncryptionService.class);
        when(repository.save(any(MailOutboxDocument.class))).thenAnswer(inv -> {
            MailOutboxDocument doc = inv.getArgument(0);
            if (doc.getId() == null) {
                doc.setId("mail-1");
            }
            return doc;
        });
        service = new MailOutboxService(repository, mongoTemplate, encryption, new int[] {5, 20, 60}, 60, 60);
    }

    @Test
    void enqueueStoresTheBodyEncryptedAndPending() {
        when(encryption.encrypt("code 123")).thenReturn("cipher");

        String id = service.enqueue("acme", "a@b.de", "Login", "code 123");

        ArgumentCaptor<MailOutboxDocument> saved = ArgumentCaptor.forClass(MailOutboxDocument.class);
        verify(repository).save(saved.capture());
        MailOutboxDocument doc = saved.getValue();
        assertThat(id).isEqualTo("mail-1");
        assertThat(doc.getBodyEncrypted()).isEqualTo("cipher");
        assertThat(doc.getStatus()).isEqualTo(MailOutboxStatus.PENDING);
        assertThat(doc.getTenantId()).isEqualTo("acme");
        assertThat(doc.getTo()).isEqualTo("a@b.de");
        assertThat(doc.getExpiresAt()).isAfter(doc.getCreatedAt());
    }

    @Test
    void claimNextReturnsTheDecryptedMessage() {
        stubClaim(entry("mail-1", 1, "cipher"));
        when(encryption.decrypt("cipher")).thenReturn("code 123");

        Optional<MailOutboxMessage> claimed = service.claimNext(NOW);

        assertThat(claimed).contains(new MailOutboxMessage("mail-1", "acme", "a@b.de", "Login", "code 123", 1));
    }

    @Test
    void claimNextIsEmptyWhenNothingIsDue() {
        stubClaim(null);

        assertThat(service.claimNext(NOW)).isEmpty();
    }

    @Test
    void claimNextGivesUpOnAnEntryThatKeepsLosingItsSender() {
        stubClaim(entry("mail-1", 5, "cipher"));
        when(repository.findById("mail-1")).thenReturn(Optional.empty());

        assertThat(service.claimNext(NOW)).isEmpty();

        ArgumentCaptor<MailOutboxDocument> saved = ArgumentCaptor.forClass(MailOutboxDocument.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MailOutboxStatus.FAILED);
        assertThat(saved.getValue().getBodyEncrypted()).isNull();
    }

    @Test
    void markSentDropsTheBody() {
        MailOutboxDocument doc = entry("mail-1", 1, "cipher");
        doc.setStatus(MailOutboxStatus.SENDING);
        when(repository.findById("mail-1")).thenReturn(Optional.of(doc));

        service.markSent("mail-1", NOW);

        assertThat(doc.getStatus()).isEqualTo(MailOutboxStatus.SENT);
        assertThat(doc.getBodyEncrypted()).isNull();
        assertThat(doc.getSentAt()).isEqualTo(NOW);
        verify(repository).save(doc);
    }

    @Test
    void markFailedSchedulesTheNextAttemptFromTheBackoff() {
        MailOutboxDocument doc = entry("mail-1", 2, "cipher");
        doc.setStatus(MailOutboxStatus.SENDING);
        when(repository.findById("mail-1")).thenReturn(Optional.of(doc));

        service.markFailed("mail-1", "smtp down", NOW);

        assertThat(doc.getStatus()).isEqualTo(MailOutboxStatus.PENDING);
        assertThat(doc.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(doc.getLastError()).isEqualTo("smtp down");
        assertThat(doc.getBodyEncrypted()).isEqualTo("cipher");
    }

    @Test
    void markFailedGivesUpAfterTheLastRetry() {
        MailOutboxDocument doc = entry("mail-1", 4, "cipher");
        doc.setStatus(MailOutboxStatus.SENDING);
        when(repository.findById("mail-1")).thenReturn(Optional.of(doc));

        service.markFailed("mail-1", "smtp down", NOW);

        assertThat(doc.getStatus()).isEqualTo(MailOutboxStatus.FAILED);
        assertThat(doc.getBodyEncrypted()).isNull();
        assertThat(doc.getLastError()).isEqualTo("smtp down");
    }

    @Test
    void markSentOnAnEvictedEntryIsANoOp() {
        when(repository.findById("gone")).thenReturn(Optional.empty());

        service.markSent("gone", NOW);

        verify(repository, never()).save(any());
    }

    private void stubClaim(MailOutboxDocument result) {
        when(mongoTemplate.findAndModify(
                        any(Query.class),
                        any(UpdateDefinition.class),
                        any(FindAndModifyOptions.class),
                        eq(MailOutboxDocument.class)))
                .thenReturn(result);
    }

    private static MailOutboxDocument entry(String id, int attempts, String bodyEncrypted) {
        return MailOutboxDocument.builder()
                .id(id)
                .tenantId("acme")
                .to("a@b.de")
                .subject("Login")
                .bodyEncrypted(bodyEncrypted)
                .attempts(attempts)
                .build();
    }
}
