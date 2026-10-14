package de.mhus.vance.shared.integration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.oauthserver.OAuthGrantRevokedEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntegrationTokenGrantRevocationListenerTest {

    private final IntegrationTokenService tokenService = mock(IntegrationTokenService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final IntegrationTokenGrantRevocationListener listener =
            new IntegrationTokenGrantRevocationListener(tokenService, auditService);

    private static IntegrationTokenDocument token(String id) {
        return IntegrationTokenDocument.builder()
                .tokenId(id)
                .tenantId("acme")
                .userId("alice")
                .parentGrantId("grant-7")
                .build();
    }

    @Test
    void revokesTheChildren_andAuditsEachOne() {
        when(tokenService.revokeAllForParentGrant("acme", "grant-7")).thenReturn(List.of(token("t1"), token("t2")));

        listener.onGrantRevoked(new OAuthGrantRevokedEvent("acme", "grant-7", "alice", "user"));

        verify(auditService).authIntegrationTokenRevoked("acme", "alice", "t1");
        verify(auditService).authIntegrationTokenRevoked("acme", "alice", "t2");
    }

    @Test
    void noChildren_noAudit() {
        when(tokenService.revokeAllForParentGrant("acme", "grant-7")).thenReturn(List.of());

        listener.onGrantRevoked(new OAuthGrantRevokedEvent("acme", "grant-7", "alice", "user"));

        verify(auditService, never()).authIntegrationTokenRevoked(anyString(), anyString(), anyString());
    }

    /** The grant is already revoked — a failure here must not surface to its publisher. */
    @Test
    void failure_doesNotPropagate() {
        when(tokenService.revokeAllForParentGrant("acme", "grant-7")).thenThrow(new IllegalStateException("mongo"));

        assertThatCode(() -> listener.onGrantRevoked(new OAuthGrantRevokedEvent("acme", "grant-7", "alice", "user")))
                .doesNotThrowAnyException();
    }
}
