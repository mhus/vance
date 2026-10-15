package de.mhus.vance.shared.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class SsoHandoffServiceTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final SsoHandoffService service = new SsoHandoffService(mock(SsoHandoffRepository.class), mongo);

    private void stored(String verifier, Instant expiresAt) {
        SsoHandoffDocument doc = SsoHandoffDocument.builder()
                .tenantId("acme")
                .username("wile")
                .appChallenge(SsoHandoffService.s256(verifier))
                .expiresAt(expiresAt)
                .build();
        when(mongo.findAndRemove(any(Query.class), eq(SsoHandoffDocument.class)))
                .thenReturn(doc);
    }

    @Test
    void s256_matchesRfc7636Example() {
        assertThat(SsoHandoffService.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void redeem_withTheRightVerifier() {
        stored("the-verifier", Instant.now().plusSeconds(30));
        assertThat(service.redeem("code", "the-verifier")).isPresent();
    }

    @Test
    void redeem_wrongVerifier_orExpired_givesNothing() {
        stored("the-verifier", Instant.now().plusSeconds(30));
        assertThat(service.redeem("code", "guessed")).isEmpty();

        stored("the-verifier", Instant.now().minusSeconds(1));
        assertThat(service.redeem("code", "the-verifier")).isEmpty();
    }
}
