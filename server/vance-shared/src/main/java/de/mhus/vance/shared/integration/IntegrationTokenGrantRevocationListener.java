package de.mhus.vance.shared.integration;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.oauthserver.OAuthGrantRevokedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Revokes the integration tokens minted through an OAuth {@code account}
 * login when that login's grant is revoked.
 *
 * <p>Disconnecting an app has to take what the app made with it: an
 * integration token outlives the access token that minted it by months, so
 * without this cascade revoking the grant would leave the app holding a
 * working credential. The tokens are revoked through their data owner and
 * audited one by one, exactly like a revocation from the token list — the
 * audit actor is the grant's account, the token owner.
 *
 * <p>The event is pod-local, but the revocation is a row write, so one pod
 * handling it is enough; other pods see the revoked rows within the
 * integration-token liveness cache window.
 *
 * <p>Never throws back into the publisher: the grant revocation has already
 * happened, and a failure here must not turn into a failed "revoke" answer
 * for the grant. It is logged at WARN instead — the tokens stay listed for
 * their owner to revoke by hand.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntegrationTokenGrantRevocationListener {

    private final IntegrationTokenService tokenService;
    private final AuditService auditService;

    @EventListener
    public void onGrantRevoked(OAuthGrantRevokedEvent event) {
        try {
            List<IntegrationTokenDocument> revoked =
                    tokenService.revokeAllForParentGrant(event.tenantId(), event.grantId());
            for (IntegrationTokenDocument doc : revoked) {
                auditService.authIntegrationTokenRevoked(event.tenantId(), event.userId(), doc.getTokenId());
            }
            if (!revoked.isEmpty()) {
                log.info(
                        "Revoked {} integration token(s) with parent grant '{}' in tenant '{}' (grant reason '{}')",
                        revoked.size(),
                        event.grantId(),
                        event.tenantId(),
                        event.reason());
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Revoking the integration tokens of grant '{}' in tenant '{}' failed: {}",
                    event.grantId(),
                    event.tenantId(),
                    e.toString());
        }
    }
}
