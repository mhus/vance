package de.mhus.vance.shared.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IntegrationTokenServiceTest {

    private final IntegrationTokenRepository repository = mock(IntegrationTokenRepository.class);

    /** TTL 0 disables the cache, so each test controls its own repository calls. */
    private final IntegrationTokenService service = new IntegrationTokenService(repository, 0);

    private static IntegrationTokenDocument row() {
        return IntegrationTokenDocument.builder()
                .tokenId("tok-1")
                .tenantId("acme")
                .userId("alice")
                .scopeProfile("links-capture")
                .projectId("links-proj")
                .label("browser")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();
    }

    /**
     * The load-bearing default: a token id with no row authenticates nobody.
     * A mint that died between signing and writing, or a row removed by hand,
     * must stop the token rather than pass it through.
     */
    @Test
    void isActive_failsClosed_whenNoRowExists() {
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.empty());

        assertThat(service.isActive("tok-1", "acme", "alice")).isFalse();
    }

    @Test
    void isActive_isFalse_afterRevocation() {
        IntegrationTokenDocument doc = row();
        doc.setRevokedAt(Instant.now());
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(doc));

        assertThat(service.isActive("tok-1", "acme", "alice")).isFalse();
    }

    @Test
    void isActive_isFalse_whenRowExpired() {
        IntegrationTokenDocument doc = row();
        doc.setExpiresAt(Instant.now().minusSeconds(1));
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(doc));

        assertThat(service.isActive("tok-1", "acme", "alice")).isFalse();
    }

    /**
     * The signature proves the claims are ours; this proves they are still the
     * claims of <em>this</em> row. Without it a token id re-used for another
     * identity would keep the old claims alive.
     */
    @Test
    void isActive_isFalse_whenClaimsDisagreeWithRow() {
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(row()));

        assertThat(service.isActive("tok-1", "acme", "mallory")).isFalse();
        assertThat(service.isActive("tok-1", "other-tenant", "alice")).isFalse();
    }

    @Test
    void isActive_isTrue_andStampsLastUsed_forALiveToken() {
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(row()));

        assertThat(service.isActive("tok-1", "acme", "alice")).isTrue();
        verify(repository).save(any(IntegrationTokenDocument.class));
    }

    /**
     * The cache is the whole performance story, and its TTL is the revocation
     * latency. One repository read per window, not per request.
     */
    @Test
    void isActive_cachesTheDecision_withinTheTtlWindow() {
        IntegrationTokenService cached = new IntegrationTokenService(repository, 60);
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(row()));

        assertThat(cached.isActive("tok-1", "acme", "alice")).isTrue();
        assertThat(cached.isActive("tok-1", "acme", "alice")).isTrue();
        assertThat(cached.isActive("tok-1", "acme", "alice")).isTrue();

        verify(repository, times(1)).findByTokenId("tok-1");
    }

    /** Revoking on this pod evicts locally, so it takes effect immediately here. */
    @Test
    void revoke_evictsTheCache_soTheNextCallSeesIt() {
        IntegrationTokenService cached = new IntegrationTokenService(repository, 60);
        IntegrationTokenDocument doc = row();
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(doc));

        assertThat(cached.isActive("tok-1", "acme", "alice")).isTrue();
        cached.revoke("acme", "tok-1");

        assertThat(cached.isActive("tok-1", "acme", "alice")).isFalse();
    }

    @Test
    void revoke_keepsTheOriginalTimestamp_whenCalledTwice() {
        IntegrationTokenDocument doc = row();
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(doc));

        service.revoke("acme", "tok-1");
        Instant first = doc.getRevokedAt();
        service.revoke("acme", "tok-1");

        assertThat(doc.getRevokedAt()).isEqualTo(first);
        verify(repository, times(1)).save(doc);
    }

    /** A token of another tenant is not this tenant's to revoke. */
    @Test
    void revoke_refusesForeignTenant() {
        when(repository.findByTokenId("tok-1")).thenReturn(Optional.of(row()));

        assertThat(service.revoke("other-tenant", "tok-1")).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void create_mintsDistinctTokenIds() {
        when(repository.save(any(IntegrationTokenDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        IntegrationTokenDocument a = service.create(
                "acme", "alice", "links-capture", "p", "one", "alice", null);
        IntegrationTokenDocument b = service.create(
                "acme", "alice", "links-capture", "p", "two", "alice", null);

        assertThat(a.getTokenId()).isNotBlank().isNotEqualTo(b.getTokenId());
    }
}
