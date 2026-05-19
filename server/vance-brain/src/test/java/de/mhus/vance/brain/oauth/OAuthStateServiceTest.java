package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.oauth.OAuthStateDocument;
import de.mhus.vance.shared.oauth.OAuthStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OAuthStateService}: state-mint shape, consume
 * happy + reject paths (unknown / expired / tenant-or-user-mismatch),
 * and the single-use guarantee (document always deleted on any consume
 * outcome that touches a stored doc).
 */
class OAuthStateServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-19T12:00:00Z");
    private static final String TENANT = "acme";
    private static final String USER = "wile.coyote";
    private static final String PROVIDER = "slack";
    private static final String RETURN_TO = "/settings/integrations";

    private OAuthStateRepository repository;
    private OAuthStateService service;

    @BeforeEach
    void setUp() {
        repository = mock(OAuthStateRepository.class);
        when(repository.save(any(OAuthStateDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new OAuthStateService(repository, clock);
    }

    @Test
    void start_persists_document_with_default_ttl() {
        String state = service.start(TENANT, USER, PROVIDER, RETURN_TO);

        assertThat(state).isNotBlank();
        org.mockito.ArgumentCaptor<OAuthStateDocument> captor =
                org.mockito.ArgumentCaptor.forClass(OAuthStateDocument.class);
        verify(repository).save(captor.capture());
        OAuthStateDocument saved = captor.getValue();
        assertThat(saved.getState()).isEqualTo(state);
        assertThat(saved.getTenantId()).isEqualTo(TENANT);
        assertThat(saved.getUserId()).isEqualTo(USER);
        assertThat(saved.getProviderId()).isEqualTo(PROVIDER);
        assertThat(saved.getReturnTo()).isEqualTo(RETURN_TO);
        assertThat(saved.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(saved.getExpiresAt())
                .isEqualTo(FIXED_NOW.plus(OAuthStateService.DEFAULT_TTL));
    }

    @Test
    void start_honours_custom_ttl() {
        service.start(TENANT, USER, PROVIDER, null, Duration.ofMinutes(1));

        org.mockito.ArgumentCaptor<OAuthStateDocument> captor =
                org.mockito.ArgumentCaptor.forClass(OAuthStateDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getExpiresAt())
                .isEqualTo(FIXED_NOW.plusSeconds(60));
    }

    @Test
    void minted_state_is_url_safe_and_unique_per_call() {
        String a = service.start(TENANT, USER, PROVIDER, null);
        String b = service.start(TENANT, USER, PROVIDER, null);

        assertThat(a).matches("[A-Za-z0-9_-]+").doesNotContain("=");
        assertThat(b).matches("[A-Za-z0-9_-]+");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void consume_happy_path_returns_provider_and_deletes_document() {
        OAuthStateDocument doc = liveDoc("state-1");
        when(repository.findByState("state-1")).thenReturn(Optional.of(doc));

        Optional<OAuthStateService.Consumed> consumed =
                service.consume("state-1", TENANT, USER);

        assertThat(consumed).isPresent();
        assertThat(consumed.get().providerId()).isEqualTo(PROVIDER);
        assertThat(consumed.get().returnTo()).isEqualTo(RETURN_TO);
        verify(repository).delete(doc);
    }

    @Test
    void consume_unknown_state_returns_empty_without_delete() {
        when(repository.findByState("nope")).thenReturn(Optional.empty());

        assertThat(service.consume("nope", TENANT, USER)).isEmpty();

        verify(repository, never()).delete(any(OAuthStateDocument.class));
    }

    @Test
    void consume_blank_or_null_state_is_short_circuited() {
        assertThat(service.consume(null, TENANT, USER)).isEmpty();
        assertThat(service.consume("", TENANT, USER)).isEmpty();
        assertThat(service.consume("   ", TENANT, USER)).isEmpty();

        verify(repository, never()).findByState(any());
    }

    @Test
    void consume_expired_state_rejects_and_deletes() {
        OAuthStateDocument doc = liveDoc("state-2");
        doc.setExpiresAt(FIXED_NOW.minusSeconds(1));
        when(repository.findByState("state-2")).thenReturn(Optional.of(doc));

        assertThat(service.consume("state-2", TENANT, USER)).isEmpty();

        verify(repository, times(1)).delete(doc);
    }

    @Test
    void consume_tenant_mismatch_rejects_and_deletes() {
        OAuthStateDocument doc = liveDoc("state-3");
        when(repository.findByState("state-3")).thenReturn(Optional.of(doc));

        assertThat(service.consume("state-3", "other-tenant", USER)).isEmpty();

        verify(repository, times(1)).delete(doc);
    }

    @Test
    void consume_user_mismatch_rejects_and_deletes() {
        OAuthStateDocument doc = liveDoc("state-4");
        when(repository.findByState("state-4")).thenReturn(Optional.of(doc));

        assertThat(service.consume("state-4", TENANT, "other-user")).isEmpty();

        verify(repository, times(1)).delete(doc);
    }

    @Test
    void consume_is_single_use_even_after_success() {
        OAuthStateDocument doc = liveDoc("state-5");
        when(repository.findByState(eq("state-5")))
                .thenReturn(Optional.of(doc))   // first call: found
                .thenReturn(Optional.empty());  // second call: gone (we deleted it)

        assertThat(service.consume("state-5", TENANT, USER)).isPresent();
        assertThat(service.consume("state-5", TENANT, USER)).isEmpty();

        verify(repository, times(1)).delete(doc);
    }

    private static OAuthStateDocument liveDoc(String state) {
        return OAuthStateDocument.builder()
                .id("doc-" + state)
                .state(state)
                .tenantId(TENANT)
                .userId(USER)
                .providerId(PROVIDER)
                .returnTo(RETURN_TO)
                .createdAt(FIXED_NOW.minusSeconds(30))
                .expiresAt(FIXED_NOW.plusSeconds(60))
                .build();
    }
}
