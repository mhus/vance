package de.mhus.vance.shared.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class IntegrationTokenServiceTest {

    private final IntegrationTokenRepository repository = mock(IntegrationTokenRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class, RETURNS_DEEP_STUBS);

    /** TTL 0 disables the cache, so each test controls its own repository calls. */
    private final IntegrationTokenService service =
            new IntegrationTokenService(repository, mongoTemplate, 0);

    private static IntegrationTokenDocument row() {
        return IntegrationTokenDocument.builder()
                .tokenId("tok-1")
                .tenantId("acme")
                .userId("alice")
                .scopeProfiles(List.of("links-capture"))
                .projectId("links-proj")
                .label("browser")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();
    }

    /** The claims a live token of {@link #row()} would present. */
    private boolean isActive() {
        return service.isActive("tok-1", "acme", "alice", "links-proj");
    }

    /**
     * The load-bearing default: a token id with no row authenticates nobody.
     * A mint that died between signing and writing, or a row removed by hand,
     * must stop the token rather than pass it through.
     */
    @Test
    void isActive_failsClosed_whenNoRowExists() {
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.empty());

        assertThat(isActive()).isFalse();
    }

    @Test
    void isActive_isFalse_afterRevocation() {
        IntegrationTokenDocument doc = row();
        doc.setRevokedAt(Instant.now());
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(doc));

        assertThat(isActive()).isFalse();
    }

    @Test
    void isActive_isFalse_whenRowExpired() {
        IntegrationTokenDocument doc = row();
        doc.setExpiresAt(Instant.now().minusSeconds(1));
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(doc));

        assertThat(isActive()).isFalse();
    }

    /**
     * The signature proves the claims are ours; this proves they are still the
     * claims of <em>this</em> row. Without it a token id re-used for another
     * identity would keep the old claims alive.
     */
    @Test
    void isActive_isFalse_whenClaimsDisagreeWithRow() {
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(row()));

        assertThat(service.isActive("tok-1", "acme", "mallory", "links-proj")).isFalse();
        assertThat(service.isActive("tok-1", "other-tenant", "alice", "links-proj")).isFalse();
    }

    /**
     * The rename hole. A signed claim cannot follow a project rename, so a row
     * that has moved no longer describes the token naming it — and without this
     * check, a *new* project taking the old name would inherit a live token
     * that was never minted for it.
     */
    @Test
    void isActive_isFalse_whenTheProjectPinNoLongerMatchesTheRow() {
        IntegrationTokenDocument renamed = row();
        renamed.setProjectId("archive");
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(renamed));

        assertThat(service.isActive("tok-1", "acme", "alice", "links-proj")).isFalse();
    }

    /** An unpinned profile's token has a null pin on both sides, and matches. */
    @Test
    void isActive_isTrue_forAnUnpinnedTokenWithAnUnpinnedRow() {
        IntegrationTokenDocument unpinned = row();
        unpinned.setProjectId(null);
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(unpinned));

        assertThat(service.isActive("tok-1", "acme", "alice", null)).isTrue();
        assertThat(service.isActive("tok-1", "acme", "alice", "links-proj")).isFalse();
    }

    @Test
    void isActive_isTrue_forALiveToken() {
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(row()));

        assertThat(isActive()).isTrue();
    }

    /**
     * <b>The regression this exists for.</b> Stamping {@code lastUsedAt} with a
     * {@code save()} of the row we just read is a lost update on the one field
     * that must never lose: a revoke landing between the read and the write is
     * overwritten back to null, permanently, and the token comes back to life.
     * One atomic {@code $set}, never a whole-document write.
     */
    @Test
    void isActive_stampsLastUsedWithAnAtomicSet_neverAWholeDocumentWrite() {
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(row()));

        assertThat(isActive()).isTrue();

        verify(repository, never()).save(any(IntegrationTokenDocument.class));
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), update.capture(),
                eq(IntegrationTokenDocument.class));
        // Key presence, not toJson(): the document holds an Instant, and the
        // driver has no codec for one outside a configured MongoTemplate.
        assertThat(setKeysOf(update.getValue())).containsExactly("lastUsedAt");
    }

    /**
     * The cache is the whole performance story, and its TTL is the revocation
     * latency. One repository read per window, not per request.
     */
    @Test
    void isActive_cachesTheDecision_withinTheTtlWindow() {
        IntegrationTokenService cached =
                new IntegrationTokenService(repository, mongoTemplate, 60);
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(row()));

        assertThat(cached.isActive("tok-1", "acme", "alice", "links-proj")).isTrue();
        assertThat(cached.isActive("tok-1", "acme", "alice", "links-proj")).isTrue();
        assertThat(cached.isActive("tok-1", "acme", "alice", "links-proj")).isTrue();

        verify(repository, times(1)).findByTokenId("tok-1");
    }

    /** Revoking on this pod evicts locally, so it takes effect immediately here. */
    @Test
    void revoke_evictsTheCache_soTheNextCallSeesIt() {
        IntegrationTokenService cached =
                new IntegrationTokenService(repository, mongoTemplate, 60);
        IntegrationTokenDocument doc = row();
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(doc));

        assertThat(cached.isActive("tok-1", "acme", "alice", "links-proj")).isTrue();
        // The row the next read sees is the revoked one.
        doc.setRevokedAt(Instant.now());
        cached.revoke("acme", "tok-1");

        assertThat(cached.isActive("tok-1", "acme", "alice", "links-proj")).isFalse();
    }

    /**
     * "Keeps the original timestamp" is structural rather than checked in
     * Java: the update carries its own {@code revokedAt == null} condition, so
     * a second call cannot overwrite the first — and two concurrent revokes
     * cannot each believe they were the first.
     */
    @Test
    void revoke_isConditionalOnNotBeingRevokedYet() {
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(row()));

        assertThat(service.revoke("acme", "tok-1")).isTrue();

        verify(repository, never()).save(any(IntegrationTokenDocument.class));
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateFirst(query.capture(), any(Update.class),
                eq(IntegrationTokenDocument.class));
        assertThat(query.getValue().getQueryObject().keySet())
                .containsExactlyInAnyOrder("tokenId", "revokedAt");
    }

    /** The fields one {@code $set} touches. */
    private static java.util.Set<String> setKeysOf(Update update) {
        return ((org.bson.Document) update.getUpdateObject().get("$set")).keySet();
    }

    /** A token of another tenant is not this tenant's to revoke. */
    @Test
    void revoke_refusesForeignTenant() {
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(row()));

        assertThat(service.revoke("other-tenant", "tok-1")).isFalse();
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(IntegrationTokenDocument.class));
    }

    @Test
    void create_mintsDistinctTokenIds() {
        when(repository.save(any(IntegrationTokenDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        IntegrationTokenDocument a = service.create(
                "acme", "alice", List.of("links-capture"), "p", "one", "alice", null);
        IntegrationTokenDocument b = service.create(
                "acme", "alice", List.of("links-capture"), "p", "two", "alice", null);

        assertThat(a.getTokenId()).isNotBlank().isNotEqualTo(b.getTokenId());
    }
}
