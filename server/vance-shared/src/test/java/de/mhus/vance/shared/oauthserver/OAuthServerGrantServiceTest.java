package de.mhus.vance.shared.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class OAuthServerGrantServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final Duration SLIDING = Duration.ofDays(30);

    private final OAuthServerGrantRepository repository = mock(OAuthServerGrantRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class, RETURNS_DEEP_STUBS);
    private final OAuthServerGrantService service =
            new OAuthServerGrantService(repository, mongoTemplate, 0, Clock.fixed(NOW, ZoneOffset.UTC));

    private OAuthServerGrantDocument grant;

    @BeforeEach
    void setUp() {
        grant = OAuthServerGrantDocument.builder()
                .grantId("g1")
                .tenantId("acme")
                .userId("alice")
                .clientId("dcr_x")
                .scope("mcp-project")
                .scopeProfiles(List.of("mcp-project"))
                .projectId("docs")
                .absoluteExpiresAt(NOW.plus(Duration.ofDays(365)))
                .refreshExpiresAt(NOW.plus(SLIDING))
                .build();
        when(repository.findByGrantId("g1")).thenReturn(Optional.of(grant));
        UpdateResult modified = mock(UpdateResult.class);
        when(modified.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(OAuthServerGrantDocument.class)))
                .thenReturn(modified);
    }

    private String liveRefreshToken() {
        String token = "g1.secret-live";
        grant.setRefreshTokenHash(OAuthSecrets.hash(token));
        return token;
    }

    @Test
    void rotate_liveToken_returnsNewToken_andRemembersThePresentedOneAsPrevious() {
        String live = liveRefreshToken();

        OAuthServerGrantService.RefreshOutcome outcome = service.rotateRefreshToken("acme", live, "dcr_x", SLIDING);

        assertThat(outcome.failure()).isNull();
        assertThat(outcome.refreshToken()).startsWith("g1.").isNotEqualTo(live);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), update.capture(), eq(OAuthServerGrantDocument.class));
        assertThat(update.getValue()
                        .getUpdateObject()
                        .get("$set", org.bson.Document.class)
                        .getString("previousRefreshTokenHash"))
                .isEqualTo(OAuthSecrets.hash(live));
    }

    /** OAuth 2.1 §4.3.1: a used refresh token presented again means a copy exists — the grant dies. */
    @Test
    void rotate_previousToken_isAReplay_andRevokesTheGrant() {
        grant.setRefreshTokenHash(OAuthSecrets.hash("g1.newer"));
        grant.setPreviousRefreshTokenHash(OAuthSecrets.hash("g1.older"));

        OAuthServerGrantService.RefreshOutcome outcome =
                service.rotateRefreshToken("acme", "g1.older", "dcr_x", SLIDING);

        assertThat(outcome.failure()).isEqualTo(OAuthServerGrantService.RefreshFailure.REPLAYED);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), update.capture(), eq(OAuthServerGrantDocument.class));
        assertThat(update.getValue()
                        .getUpdateObject()
                        .get("$set", org.bson.Document.class)
                        .getString("revokeReason"))
                .isEqualTo("refresh-replay");
    }

    @Test
    void rotate_tokenOfAnotherClient_isRefused_withoutTouchingTheGrant() {
        String live = liveRefreshToken();

        OAuthServerGrantService.RefreshOutcome outcome = service.rotateRefreshToken("acme", live, "dcr_other", SLIDING);

        assertThat(outcome.failure()).isEqualTo(OAuthServerGrantService.RefreshFailure.INVALID);
        verify(mongoTemplate, never())
                .updateFirst(any(Query.class), any(Update.class), eq(OAuthServerGrantDocument.class));
    }

    @Test
    void rotate_unknownSecret_isInvalid() {
        liveRefreshToken();

        assertThat(service.rotateRefreshToken("acme", "g1.guessed", "dcr_x", SLIDING)
                        .failure())
                .isEqualTo(OAuthServerGrantService.RefreshFailure.INVALID);
    }

    @Test
    void rotate_revokedGrant_isExpired() {
        String live = liveRefreshToken();
        grant.setRevokedAt(NOW.minusSeconds(5));

        assertThat(service.rotateRefreshToken("acme", live, "dcr_x", SLIDING).failure())
                .isEqualTo(OAuthServerGrantService.RefreshFailure.EXPIRED);
    }

    @Test
    void rotate_pastTheSlidingWindow_isExpired() {
        String live = liveRefreshToken();
        grant.setRefreshExpiresAt(NOW.minusSeconds(1));

        assertThat(service.rotateRefreshToken("acme", live, "dcr_x", SLIDING).failure())
                .isEqualTo(OAuthServerGrantService.RefreshFailure.EXPIRED);
    }

    @Test
    void rotate_lostRaceOnTheConditionalUpdate_isInvalid() {
        String live = liveRefreshToken();
        UpdateResult none = mock(UpdateResult.class);
        when(none.getModifiedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(OAuthServerGrantDocument.class)))
                .thenReturn(none);

        assertThat(service.rotateRefreshToken("acme", live, "dcr_x", SLIDING).failure())
                .isEqualTo(OAuthServerGrantService.RefreshFailure.INVALID);
    }

    @Test
    void rotate_tokenFromAnotherTenant_isInvalid() {
        String live = liveRefreshToken();

        assertThat(service.rotateRefreshToken("other", live, "dcr_x", SLIDING).failure())
                .isEqualTo(OAuthServerGrantService.RefreshFailure.INVALID);
    }

    @Test
    void isActive_liveGrant_withMatchingIdentityAndPin() {
        assertThat(service.isActive("g1", "acme", "alice", "docs")).isTrue();
    }

    @Test
    void isActive_failsClosed_onUnknownGrant() {
        when(repository.findByGrantId("g1")).thenReturn(Optional.empty());

        assertThat(service.isActive("g1", "acme", "alice", "docs")).isFalse();
    }

    @Test
    void isActive_isFalse_afterRevocation() {
        grant.setRevokedAt(NOW.minusSeconds(1));

        assertThat(service.isActive("g1", "acme", "alice", "docs")).isFalse();
    }

    /** A renamed project: the signed pin names the old project, the row the new one. */
    @Test
    void isActive_isFalse_whenPinNoLongerMatchesTheRow() {
        assertThat(service.isActive("g1", "acme", "alice", "docs-old")).isFalse();
    }

    @Test
    void isActive_isFalse_forAnotherUser() {
        assertThat(service.isActive("g1", "acme", "mallory", "docs")).isFalse();
    }

    @Test
    void isActive_isFalse_pastTheAbsoluteEnd() {
        grant.setAbsoluteExpiresAt(NOW.minusSeconds(1));

        assertThat(service.isActive("g1", "acme", "alice", "docs")).isFalse();
    }

    @Test
    void revokeByRefreshToken_needsTheRealSecret() {
        liveRefreshToken();

        assertThat(service.revokeByRefreshToken("acme", "g1.guessed")).isFalse();
        verify(mongoTemplate, never())
                .updateFirst(any(Query.class), any(Update.class), eq(OAuthServerGrantDocument.class));
    }

    @Test
    void grantIdOf_readsThePrefix_andRejectsShapelessTokens() {
        assertThat(OAuthServerGrantService.grantIdOf("g1.abc")).isEqualTo("g1");
        assertThat(OAuthServerGrantService.grantIdOf("noseparator")).isNull();
        assertThat(OAuthServerGrantService.grantIdOf(".abc")).isNull();
        assertThat(OAuthServerGrantService.grantIdOf("g1.")).isNull();
    }
}
