package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.shared.permission.PermissionRequestPort;
import de.mhus.vance.simpleauth.GrantRole;
import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import de.mhus.vance.simpleauth.PermissionRequestOperation;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Simple-Auth implementation of {@link PermissionRequestPort}: lets code
 * outside this addon raise a rights request without depending on the
 * provider's storage or role model.
 *
 * <p>Same path the {@code permission_request_*} tools take, so a request
 * from an engine is indistinguishable from one raised by a tool — same
 * idempotency, same routing, same approval.
 */
@Service
@RequiredArgsConstructor
public class SimpleAuthPermissionRequestPort implements PermissionRequestPort {

    private final PermissionRequestSupport support;

    @Override
    public PermissionRequestReceipt requestProjectWriter(
            String tenant, String project, String username,
            @Nullable String reason, String requestedBy, @Nullable String processId) {
        PermissionRequestSupport.Receipt receipt = support.raise(
                tenant, requestedBy, processId, /*sessionId*/ null,
                PermissionRequestOperation.GRANT,
                GrantScopeType.PROJECT, project,
                GrantSubjectType.USER, username,
                GrantRole.WRITER, reason);
        return new PermissionRequestReceipt(receipt.requestId(), receipt.itemId(),
                receipt.status(), receipt.decider(), receipt.reused());
    }
}
