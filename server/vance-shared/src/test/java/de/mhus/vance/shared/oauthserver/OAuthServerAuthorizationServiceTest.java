package de.mhus.vance.shared.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class OAuthServerAuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private final OAuthServerAuthRequestRepository requests = mock(OAuthServerAuthRequestRepository.class);
    private final OAuthServerCodeRepository codes = mock(OAuthServerCodeRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final OAuthServerAuthorizationService service =
            new OAuthServerAuthorizationService(requests, codes, mongoTemplate, Clock.fixed(NOW, ZoneOffset.UTC));

    private static OAuthServerCodeDocument code(Instant usedAt, String tenant) {
        return OAuthServerCodeDocument.builder()
                .codeHash(OAuthSecrets.hash("raw"))
                .tenantId(tenant)
                .grantId("g1")
                .clientId("dcr_x")
                .usedAt(usedAt)
                .expiresAt(NOW.plusSeconds(30))
                .build();
    }

    // ──────────────────── codes ────────────────────

    @Test
    void findCode_readsWithoutSpending() {
        when(codes.findByCodeHash(OAuthSecrets.hash("raw"))).thenReturn(Optional.of(code(null, "acme")));

        assertThat(service.findCode("acme", "raw")).isPresent();
        verify(mongoTemplate, never())
                .findAndModify(any(Query.class), any(Update.class), eq(OAuthServerCodeDocument.class));
    }

    @Test
    void findCode_ofAnotherTenant_isEmpty() {
        when(codes.findByCodeHash(OAuthSecrets.hash("raw"))).thenReturn(Optional.of(code(null, "other")));

        assertThat(service.findCode("acme", "raw")).isEmpty();
    }

    @Test
    void consumeCode_firstTime_spendsIt_conditionalOnUnusedAndUnexpired() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(OAuthServerCodeDocument.class)))
                .thenReturn(code(null, "acme"));

        assertThat(service.consumeCode("acme", "raw")).isTrue();

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findAndModify(query.capture(), any(Update.class), eq(OAuthServerCodeDocument.class));
        assertThat(query.getValue().getQueryObject())
                .containsEntry("codeHash", OAuthSecrets.hash("raw"))
                .containsEntry("usedAt", null)
                .containsKey("expiresAt");
    }

    @Test
    void consumeCode_alreadySpent_isFalse() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(OAuthServerCodeDocument.class)))
                .thenReturn(null);

        assertThat(service.consumeCode("acme", "raw")).isFalse();
    }

    @Test
    void isExpired_comparesWithTheClock() {
        OAuthServerCodeDocument live = code(null, "acme");
        OAuthServerCodeDocument old = code(null, "acme");
        old.setExpiresAt(NOW);

        assertThat(service.isExpired(live)).isFalse();
        assertThat(service.isExpired(old)).isTrue();
    }

    // ──────────────────── requests ────────────────────

    @Test
    void bindUser_secondUser_isRefused() {
        OAuthServerAuthRequestDocument bound = OAuthServerAuthRequestDocument.builder()
                .requestId("r1")
                .tenantId("acme")
                .userId("alice")
                .expiresAt(NOW.plusSeconds(60))
                .build();
        when(requests.findByRequestId("r1")).thenReturn(Optional.of(bound));

        assertThat(service.bindUser("acme", "r1", "mallory")).isEmpty();
        assertThat(service.bindUser("acme", "r1", "alice")).contains(bound);
    }

    @Test
    void bindUser_firstUser_isBound() {
        OAuthServerAuthRequestDocument open = OAuthServerAuthRequestDocument.builder()
                .requestId("r1")
                .tenantId("acme")
                .expiresAt(NOW.plusSeconds(60))
                .build();
        OAuthServerAuthRequestDocument bound = OAuthServerAuthRequestDocument.builder()
                .requestId("r1")
                .tenantId("acme")
                .userId("alice")
                .expiresAt(NOW.plusSeconds(60))
                .build();
        when(requests.findByRequestId("r1")).thenReturn(Optional.of(open));
        when(mongoTemplate.findAndModify(
                        any(Query.class),
                        any(Update.class),
                        any(FindAndModifyOptions.class),
                        eq(OAuthServerAuthRequestDocument.class)))
                .thenReturn(bound);

        assertThat(service.bindUser("acme", "r1", "alice")).contains(bound);
    }

    /** The raw ticket leaves the service once; the store only ever sees its hash. */
    @Test
    void issueConsentTicket_storesOnlyTheHash() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(OAuthServerAuthRequestDocument.class)))
                .thenReturn(
                        OAuthServerAuthRequestDocument.builder().requestId("r1").build());

        String ticket = service.issueConsentTicket("acme", "r1", "alice").orElseThrow();

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate)
                .findAndModify(query.capture(), update.capture(), eq(OAuthServerAuthRequestDocument.class));
        assertThat(update.getValue().getUpdateObject().get("$set", org.bson.Document.class))
                .containsEntry("consentTicketHash", OAuthSecrets.hash(ticket));
        assertThat(query.getValue().getQueryObject()).containsEntry("userId", "alice");
    }

    @Test
    void issueConsentTicket_requestOfAnotherUser_isEmpty() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(OAuthServerAuthRequestDocument.class)))
                .thenReturn(null);

        assertThat(service.issueConsentTicket("acme", "r1", "mallory")).isEmpty();
    }

    private OAuthServerAuthRequestDocument boundWithTicket(String ticket) {
        OAuthServerAuthRequestDocument doc = OAuthServerAuthRequestDocument.builder()
                .requestId("r1")
                .tenantId("acme")
                .userId("alice")
                .consentTicketHash(OAuthSecrets.hash(ticket))
                .expiresAt(NOW.plusSeconds(60))
                .build();
        when(requests.findByRequestId("r1")).thenReturn(Optional.of(doc));
        return doc;
    }

    @Test
    void takeForDecision_rightTicket_removesTheRequestAtomically() {
        OAuthServerAuthRequestDocument doc = boundWithTicket("ticket");
        when(mongoTemplate.findAndRemove(any(Query.class), eq(OAuthServerAuthRequestDocument.class)))
                .thenReturn(doc);

        assertThat(service.takeForDecision("acme", "r1", "alice", "ticket")).contains(doc);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findAndRemove(query.capture(), eq(OAuthServerAuthRequestDocument.class));
        assertThat(query.getValue().getQueryObject())
                .containsEntry("requestId", "r1")
                .containsEntry("userId", "alice")
                .containsEntry("consentTicketHash", OAuthSecrets.hash("ticket"));
    }

    /** A double-submitted consent: the second take finds nothing — one decision, one code. */
    @Test
    void takeForDecision_secondSubmission_getsNothing() {
        boundWithTicket("ticket");
        when(mongoTemplate.findAndRemove(any(Query.class), eq(OAuthServerAuthRequestDocument.class)))
                .thenReturn(null);

        assertThat(service.takeForDecision("acme", "r1", "alice", "ticket")).isEmpty();
    }

    @Test
    void takeForDecision_wrongTicketOrUser_removesNothing() {
        boundWithTicket("ticket");

        assertThat(service.takeForDecision("acme", "r1", "alice", "forged")).isEmpty();
        assertThat(service.takeForDecision("acme", "r1", "mallory", "ticket")).isEmpty();
        verify(mongoTemplate, never()).findAndRemove(any(Query.class), eq(OAuthServerAuthRequestDocument.class));
    }

    @Test
    void findRequest_expired_isEmpty() {
        OAuthServerAuthRequestDocument expired = OAuthServerAuthRequestDocument.builder()
                .requestId("r1")
                .tenantId("acme")
                .expiresAt(NOW.minusSeconds(1))
                .build();
        when(requests.findByRequestId("r1")).thenReturn(Optional.of(expired));

        assertThat(service.findRequest("acme", "r1")).isEmpty();
    }
}
