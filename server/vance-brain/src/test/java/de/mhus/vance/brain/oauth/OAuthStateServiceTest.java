package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.oauth.OAuthStateDocument;
import de.mhus.vance.shared.oauth.OAuthStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Unit tests for {@link OAuthStateService}: state-mint shape, consume
 * happy + reject paths (unknown / expired / tenant-or-user-mismatch), and the
 * atomic single-use guarantee — {@code consume} removes the document with one
 * atomic {@code findAndRemove}, so two concurrent callbacks with the same
 * state can never both succeed. Mongo is mocked.
 */
class OAuthStateServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-19T12:00:00Z");
    private static final String TENANT = "acme";
    private static final String USER = "wile.coyote";
    private static final String PROVIDER = "slack";
    private static final String RETURN_TO = "/settings/integrations";

    private OAuthStateRepository repository;
    private MongoTemplate mongoTemplate;
    private OAuthStateService service;

    @BeforeEach
    void setUp() {
        repository = mock(OAuthStateRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        when(repository.save(any(OAuthStateDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new OAuthStateService(repository, mongoTemplate, clock);
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
    void start_persists_code_verifier_and_returns_it_on_consume() {
        // PKCE round-trip — init writes verifier alongside state, callback
        // reads it back to replay against the provider's /token endpoint.
        stubConsume(OAuthStateDocument.builder()
                .state("S1")
                .tenantId(TENANT).userId(USER).providerId(PROVIDER)
                .codeVerifier("verifier-42")
                .createdAt(FIXED_NOW)
                .expiresAt(FIXED_NOW.plus(OAuthStateService.DEFAULT_TTL))
                .build());

        String state = service.start(TENANT, USER, PROVIDER, null, "verifier-42");

        org.mockito.ArgumentCaptor<OAuthStateDocument> captor =
                org.mockito.ArgumentCaptor.forClass(OAuthStateDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCodeVerifier()).isEqualTo("verifier-42");

        var consumed = service.consume("S1", TENANT, USER);
        assertThat(consumed).isPresent();
        assertThat(consumed.get().codeVerifier()).isEqualTo("verifier-42");
    }

    @Test
    void start_honours_custom_ttl() {
        service.start(TENANT, USER, PROVIDER, null, null, Duration.ofMinutes(1));

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
    void consume_happy_path_returns_provider_andRemovesAtomically() {
        stubConsume(liveDoc("state-1"));

        var consumed = service.consume("state-1", TENANT, USER);

        assertThat(consumed).isPresent();
        assertThat(consumed.get().providerId()).isEqualTo(PROVIDER);
        assertThat(consumed.get().returnTo()).isEqualTo(RETURN_TO);
        // Single-use is carried by the atomic find-and-remove itself — no
        // separate repository.delete round-trip.
        verify(mongoTemplate).findAndRemove(any(Query.class), eq(OAuthStateDocument.class));
        verify(repository, never()).delete(any(OAuthStateDocument.class));
    }

    @Test
    void consume_unknown_state_returns_empty() {
        when(mongoTemplate.findAndRemove(any(Query.class), eq(OAuthStateDocument.class)))
                .thenReturn(null);

        assertThat(service.consume("nope", TENANT, USER)).isEmpty();
    }

    @Test
    void consume_blank_or_null_state_is_short_circuited() {
        assertThat(service.consume(null, TENANT, USER)).isEmpty();
        assertThat(service.consume("", TENANT, USER)).isEmpty();
        assertThat(service.consume("   ", TENANT, USER)).isEmpty();

        // No atomic op is issued for a blank state.
        verify(mongoTemplate, never()).findAndRemove(any(Query.class), eq(OAuthStateDocument.class));
    }

    @Test
    void consume_expired_state_rejects() {
        OAuthStateDocument doc = liveDoc("state-2");
        doc.setExpiresAt(FIXED_NOW.minusSeconds(1));
        stubConsume(doc);

        // Even though removed atomically, an expired doc must not authenticate.
        assertThat(service.consume("state-2", TENANT, USER)).isEmpty();
    }

    @Test
    void consume_tenant_mismatch_rejects() {
        stubConsume(liveDoc("state-3"));

        assertThat(service.consume("state-3", "other-tenant", USER)).isEmpty();
    }

    @Test
    void consume_user_mismatch_rejects() {
        stubConsume(liveDoc("state-4"));

        assertThat(service.consume("state-4", TENANT, "other-user")).isEmpty();
    }

    @Test
    void consume_is_single_use_even_after_success() {
        // First find-and-remove wins (returns the doc); the second finds
        // nothing (already removed) → empty. This is exactly the concurrent
        // double-callback protection the atomic op provides.
        when(mongoTemplate.findAndRemove(any(Query.class), eq(OAuthStateDocument.class)))
                .thenReturn(liveDoc("state-5"))
                .thenReturn(null);

        assertThat(service.consume("state-5", TENANT, USER)).isPresent();
        assertThat(service.consume("state-5", TENANT, USER)).isEmpty();
    }

    private void stubConsume(OAuthStateDocument doc) {
        when(mongoTemplate.findAndRemove(any(Query.class), eq(OAuthStateDocument.class)))
                .thenReturn(doc);
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
