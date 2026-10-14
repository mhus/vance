package de.mhus.vance.shared.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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

    private static OAuthServerCodeDocument code(Instant usedAt) {
        return OAuthServerCodeDocument.builder()
                .codeHash(OAuthSecrets.hash("raw"))
                .tenantId("acme")
                .grantId("g1")
                .clientId("dcr_x")
                .usedAt(usedAt)
                .expiresAt(NOW.plusSeconds(30))
                .build();
    }

    @Test
    void redeem_firstTime_returnsTheCode() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(OAuthServerCodeDocument.class)))
                .thenReturn(code(null));

        OAuthServerAuthorizationService.Redemption redemption = service.redeemCode("acme", "raw");

        assertThat(redemption.ok()).isTrue();
        assertThat(redemption.replayedGrantId()).isNull();
    }

    /** OAuth 2.1 §4.1.3: a second redemption is reported with its grant so it can be revoked. */
    @Test
    void redeem_secondTime_isAReplay_namingTheGrant() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(OAuthServerCodeDocument.class)))
                .thenReturn(null);
        when(codes.findByCodeHash(OAuthSecrets.hash("raw"))).thenReturn(Optional.of(code(NOW.minusSeconds(3))));

        OAuthServerAuthorizationService.Redemption redemption = service.redeemCode("acme", "raw");

        assertThat(redemption.ok()).isFalse();
        assertThat(redemption.replayedGrantId()).isEqualTo("g1");
    }

    @Test
    void redeem_unknownOrExpiredCode_isNeitherOkNorReplay() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(OAuthServerCodeDocument.class)))
                .thenReturn(null);
        when(codes.findByCodeHash(any())).thenReturn(Optional.empty());

        OAuthServerAuthorizationService.Redemption redemption = service.redeemCode("acme", "raw");

        assertThat(redemption.ok()).isFalse();
        assertThat(redemption.replayedGrantId()).isNull();
    }

    @Test
    void bindUser_secondUser_isRefused() {
        OAuthServerAuthRequestDocument bound = OAuthServerAuthRequestDocument.builder()
                .requestId("r1")
                .tenantId("acme")
                .userId("alice")
                .consentTicket("t")
                .expiresAt(NOW.plusSeconds(60))
                .build();
        when(requests.findByRequestId("r1")).thenReturn(Optional.of(bound));

        assertThat(service.bindUser("acme", "r1", "mallory")).isEmpty();
        assertThat(service.bindUser("acme", "r1", "alice")).contains(bound);
    }

    @Test
    void bindUser_firstUser_getsATicket() {
        OAuthServerAuthRequestDocument open = OAuthServerAuthRequestDocument.builder()
                .requestId("r1")
                .tenantId("acme")
                .expiresAt(NOW.plusSeconds(60))
                .build();
        OAuthServerAuthRequestDocument bound = OAuthServerAuthRequestDocument.builder()
                .requestId("r1")
                .tenantId("acme")
                .userId("alice")
                .consentTicket("ticket")
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
