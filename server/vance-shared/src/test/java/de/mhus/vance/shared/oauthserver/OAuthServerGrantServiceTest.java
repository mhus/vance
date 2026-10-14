package de.mhus.vance.shared.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class OAuthServerGrantServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final Duration SLIDING = Duration.ofDays(30);
    private static final String FOOT = "vancetope-foot";

    private final OAuthServerGrantRepository repository = mock(OAuthServerGrantRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class, RETURNS_DEEP_STUBS);
    private final UserService userService = mock(UserService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AuditService audit = mock(AuditService.class);
    private final OAuthServerGrantService service = new OAuthServerGrantService(
            repository, mongoTemplate, userService, events, audit, 0, Clock.fixed(NOW, ZoneOffset.UTC));

    private OAuthServerGrantDocument grant;
    private UserDocument alice;

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
        modifiedCount(1L);
        alice = UserDocument.builder()
                .tenantId("acme")
                .name("alice")
                .status(UserStatus.ACTIVE)
                .loginEnabled(true)
                .build();
        when(userService.findByTenantAndName("acme", "alice")).thenReturn(Optional.of(alice));
    }

    private void modifiedCount(long count) {
        UpdateResult result = mock(UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(count);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(OAuthServerGrantDocument.class)))
                .thenReturn(result);
    }

    private String liveRefreshToken() {
        String token = "g1.secret-live";
        grant.setRefreshTokenHash(OAuthSecrets.hash(token));
        return token;
    }

    private Update capturedUpdate() {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), update.capture(), eq(OAuthServerGrantDocument.class));
        return update.getValue();
    }

    // ──────────────────── refresh ────────────────────

    @Test
    void rotate_liveToken_returnsNewToken_andAppendsThePresentedOneToTheHistory() {
        String live = liveRefreshToken();

        OAuthServerGrantService.RefreshOutcome outcome = service.rotateRefreshToken("acme", live, "dcr_x", SLIDING);

        assertThat(outcome.failure()).isNull();
        assertThat(outcome.refreshToken()).startsWith("g1.").isNotEqualTo(live);
        Update update = capturedUpdate();
        assertThat(update.modifies("previousRefreshTokenHashes")).isTrue();
        assertThat(update.getUpdateObject().get("$push", org.bson.Document.class))
                .containsKey("previousRefreshTokenHashes");
        // No legacy field on this row — nothing to fold in, nothing to clear.
        assertThat(update.modifies("previousRefreshTokenHash")).isFalse();
    }

    /** Rows written before the history existed: the single previous hash is folded into the list. */
    @Test
    void rotate_rowWithLegacyPreviousHash_clearsTheLegacyField() {
        String live = liveRefreshToken();
        grant.setPreviousRefreshTokenHash(OAuthSecrets.hash("g1.legacy"));

        service.rotateRefreshToken("acme", live, "dcr_x", SLIDING);

        Update update = capturedUpdate();
        assertThat(update.modifies("previousRefreshTokenHashes")).isTrue();
        assertThat(update.getUpdateObject().get("$set", org.bson.Document.class))
                .containsEntry("previousRefreshTokenHash", null);
    }

    /** OAuth 2.1 §4.3.1: a used refresh token presented again means a copy exists — the grant dies. */
    @Test
    void inspect_replacedToken_isAReplay_andRevokesTheGrant() {
        grant.setRefreshTokenHash(OAuthSecrets.hash("g1.newer"));
        grant.setPreviousRefreshTokenHashes(new ArrayList<>(List.of(OAuthSecrets.hash("g1.older"))));

        OAuthServerGrantService.RefreshOutcome outcome = service.inspectRefreshToken("acme", "g1.older", "dcr_x");

        assertThat(outcome.failure()).isEqualTo(OAuthServerGrantService.RefreshFailure.REPLAYED);
        assertThat(capturedUpdate()
                        .getUpdateObject()
                        .get("$set", org.bson.Document.class)
                        .getString("revokeReason"))
                .isEqualTo("refresh-replay");
        verify(audit).authOAuthTokenReplay("acme", "alice", "g1", "dcr_x", "refresh");
        verify(audit).authOAuthGrantRevoked("acme", "alice", "g1", "refresh-replay");
        verify(events).publishEvent(new OAuthGrantRevokedEvent("acme", "g1", "alice", "refresh-replay"));
    }

    /** An attacker who refreshed twice with a stolen token is still caught when the victim comes back. */
    @Test
    void inspect_tokenSeveralGenerationsBack_isStillAReplay() {
        grant.setRefreshTokenHash(OAuthSecrets.hash("g1.gen3"));
        grant.setPreviousRefreshTokenHashes(new ArrayList<>(
                List.of(OAuthSecrets.hash("g1.gen0"), OAuthSecrets.hash("g1.gen1"), OAuthSecrets.hash("g1.gen2"))));

        assertThat(service.inspectRefreshToken("acme", "g1.gen0", "dcr_x").failure())
                .isEqualTo(OAuthServerGrantService.RefreshFailure.REPLAYED);
    }

    @Test
    void inspect_legacyPreviousHash_isStillAReplay() {
        grant.setRefreshTokenHash(OAuthSecrets.hash("g1.newer"));
        grant.setPreviousRefreshTokenHash(OAuthSecrets.hash("g1.older"));

        assertThat(service.inspectRefreshToken("acme", "g1.older", "dcr_x").failure())
                .isEqualTo(OAuthServerGrantService.RefreshFailure.REPLAYED);
    }

    /** Inspection is what the token endpoint does before checking resource and account: it spends nothing. */
    @Test
    void inspect_liveToken_returnsTheGrant_withoutWriting() {
        String live = liveRefreshToken();

        OAuthServerGrantService.RefreshOutcome outcome = service.inspectRefreshToken("acme", live, "dcr_x");

        assertThat(outcome.failure()).isNull();
        assertThat(outcome.grant()).isSameAs(grant);
        assertThat(outcome.refreshToken()).isNull();
        verify(mongoTemplate, never())
                .updateFirst(any(Query.class), any(Update.class), eq(OAuthServerGrantDocument.class));
    }

    @Test
    void rotate_tokenOfAnotherClient_isRefused_withoutTouchingTheGrant() {
        String live = liveRefreshToken();

        OAuthServerGrantService.RefreshOutcome outcome = service.rotateRefreshToken("acme", live, "dcr_other", SLIDING);

        assertThat(outcome.failure()).isEqualTo(OAuthServerGrantService.RefreshFailure.INVALID);
        verify(mongoTemplate, never())
                .updateFirst(any(Query.class), any(Update.class), eq(OAuthServerGrantDocument.class));
    }

    /** A replaced token presented by a different client proves nothing about this grant — no revocation. */
    @Test
    void inspect_replacedTokenOfAnotherClient_isInvalid_notAReplay() {
        grant.setRefreshTokenHash(OAuthSecrets.hash("g1.newer"));
        grant.setPreviousRefreshTokenHashes(new ArrayList<>(List.of(OAuthSecrets.hash("g1.older"))));

        assertThat(service.inspectRefreshToken("acme", "g1.older", "dcr_other").failure())
                .isEqualTo(OAuthServerGrantService.RefreshFailure.INVALID);
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
        modifiedCount(0L);

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
    void startRefreshStrand_clearsTheReplayHistory() {
        assertThat(service.startRefreshStrand("g1", SLIDING)).startsWith("g1.");

        org.bson.Document set = capturedUpdate().getUpdateObject().get("$set", org.bson.Document.class);
        assertThat(set.get("previousRefreshTokenHashes")).isEqualTo(List.of());
        assertThat(set).containsEntry("previousRefreshTokenHash", null);
    }

    // ──────────────────── liveness ────────────────────

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
    void isActive_isFalse_forADisabledAccount() {
        alice.setStatus(UserStatus.DISABLED);

        assertThat(service.isActive("g1", "acme", "alice", "docs")).isFalse();
    }

    @Test
    void isActive_isFalse_forAnAccountWithoutLogin() {
        alice.setLoginEnabled(false);

        assertThat(service.isActive("g1", "acme", "alice", "docs")).isFalse();
    }

    @Test
    void isActive_isFalse_whenTheAccountIsGone() {
        when(userService.findByTenantAndName("acme", "alice")).thenReturn(Optional.empty());

        assertThat(service.isActive("g1", "acme", "alice", "docs")).isFalse();
    }

    /**
     * The cache is keyed on the claims, not the grant id alone: after a user
     * rename the old name must not ride on the decision cached for the new one.
     */
    @Test
    void isActive_cacheKeysOnTheUser_notOnlyOnTheGrant() {
        OAuthServerGrantService cached = new OAuthServerGrantService(
                repository, mongoTemplate, userService, events, audit, 30, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(cached.isActive("g1", "acme", "alice", "docs")).isTrue();
        assertThat(cached.isActive("g1", "acme", "alice-old", "docs")).isFalse();
        assertThat(cached.isActive("g1", "acme", "alice", "docs")).isTrue();
        verify(repository, times(2)).findByGrantId("g1");
    }

    @Test
    void isActive_cachedDecision_isDroppedOnRevocation() {
        OAuthServerGrantService cached = new OAuthServerGrantService(
                repository, mongoTemplate, userService, events, audit, 30, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(cached.isActive("g1", "acme", "alice", "docs")).isTrue();

        cached.revoke("acme", "g1", "user");
        grant.setRevokedAt(NOW);

        assertThat(cached.isActive("g1", "acme", "alice", "docs")).isFalse();
    }

    // ──────────────────── revocation ────────────────────

    @Test
    void revoke_auditsAndPublishes_once() {
        assertThat(service.revoke("acme", "g1", "user")).isTrue();

        verify(audit).authOAuthGrantRevoked("acme", "alice", "g1", "user");
        verify(events).publishEvent(new OAuthGrantRevokedEvent("acme", "g1", "alice", "user"));
    }

    @Test
    void revoke_alreadyRevoked_isQuiet() {
        modifiedCount(0L);

        assertThat(service.revoke("acme", "g1", "user")).isTrue();

        verify(audit, never()).authOAuthGrantRevoked(anyString(), anyString(), anyString(), anyString());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void revoke_grantOfAnotherTenant_isFalse() {
        assertThat(service.revoke("other", "g1", "user")).isFalse();
        verify(mongoTemplate, never())
                .updateFirst(any(Query.class), any(Update.class), eq(OAuthServerGrantDocument.class));
    }

    @Test
    void revokeByRefreshToken_needsTheRealSecret() {
        liveRefreshToken();

        assertThat(service.revokeByRefreshToken("acme", "g1.guessed", "dcr_x")).isFalse();
        verify(mongoTemplate, never())
                .updateFirst(any(Query.class), any(Update.class), eq(OAuthServerGrantDocument.class));
    }

    /** RFC 7009 §2.1: a client may only revoke its own tokens. */
    @Test
    void revokeByRefreshToken_ofAnotherClient_revokesNothing() {
        String live = liveRefreshToken();

        assertThat(service.revokeByRefreshToken("acme", live, "dcr_other")).isFalse();
        verify(mongoTemplate, never())
                .updateFirst(any(Query.class), any(Update.class), eq(OAuthServerGrantDocument.class));
    }

    @Test
    void revokeByRefreshToken_liveToken_ofTheClient_revokes() {
        String live = liveRefreshToken();

        assertThat(service.revokeByRefreshToken("acme", live, "dcr_x")).isTrue();
        verify(events).publishEvent(new OAuthGrantRevokedEvent("acme", "g1", "alice", "client"));
    }

    @Test
    void revokeAllForUser_revokesEveryLiveGrant_andCounts() {
        OAuthServerGrantDocument second = OAuthServerGrantDocument.builder()
                .grantId("g2")
                .tenantId("acme")
                .userId("alice")
                .clientId(FOOT)
                .build();
        when(repository.findByTenantIdAndUserIdAndRevokedAtIsNull("acme", "alice"))
                .thenReturn(List.of(grant, second));

        assertThat(service.revokeAllForUser("acme", "alice", "password-changed"))
                .isEqualTo(2);
        verify(events).publishEvent(new OAuthGrantRevokedEvent("acme", "g1", "alice", "password-changed"));
        verify(events).publishEvent(new OAuthGrantRevokedEvent("acme", "g2", "alice", "password-changed"));
        verify(audit).authOAuthGrantRevoked("acme", "alice", "g2", "password-changed");
    }

    @Test
    void revokeAllForProject_revokesThePinnedGrants() {
        when(repository.findByTenantIdAndProjectIdAndRevokedAtIsNull("acme", "docs"))
                .thenReturn(List.of(grant));

        assertThat(service.revokeAllForProject("acme", "docs", "project-renamed"))
                .isEqualTo(1);
        verify(audit).authOAuthGrantRevoked("acme", "alice", "g1", "project-renamed");
    }

    @Test
    void revokeAfterReplay_auditsTheReplay_andRevokes() {
        service.revokeAfterReplay("acme", "g1", "code");

        verify(audit).authOAuthTokenReplay("acme", "alice", "g1", "dcr_x", "code");
        verify(events).publishEvent(new OAuthGrantRevokedEvent("acme", "g1", "alice", "code-replay"));
    }

    @Test
    void hasLiveGrantForClient_asksForUnrevokedAndUnexpired() {
        when(mongoTemplate.exists(any(Query.class), eq(OAuthServerGrantDocument.class)))
                .thenReturn(true);

        assertThat(service.hasLiveGrantForClient("dcr_x")).isTrue();

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).exists(query.capture(), eq(OAuthServerGrantDocument.class));
        org.bson.Document q = query.getValue().getQueryObject();
        assertThat(q).containsEntry("clientId", "dcr_x").containsEntry("revokedAt", null);
        assertThat(q).containsKey("$or");
    }

    @Test
    void grantIdOf_readsThePrefix_andRejectsShapelessTokens() {
        assertThat(OAuthServerGrantService.grantIdOf("g1.abc")).isEqualTo("g1");
        assertThat(OAuthServerGrantService.grantIdOf("noseparator")).isNull();
        assertThat(OAuthServerGrantService.grantIdOf(".abc")).isNull();
        assertThat(OAuthServerGrantService.grantIdOf("g1.")).isNull();
    }
}
