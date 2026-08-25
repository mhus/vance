package de.mhus.vance.simpleauth;

import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserLifecycleListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Keeps this addon's name-keyed storage in step with the accounts it talks
 * about: grants and pending permission requests are addressed by
 * {@code (tenant, username)}, so nothing links them to the user document and
 * nothing removes them with it.
 *
 * <p>Registered simply by being in the component-scanned addon package, which
 * is what makes it the right place: {@code UserService} lives in
 * {@code vance-shared} and must not know that grant storage exists, and a
 * process without this addon (enterprise governor) gets no listener and
 * therefore no behaviour to opt out of.
 *
 * <p><b>Both ends are the same call</b>, {@link SimpleAuthPermissionBootstrap#revokeAll}
 * — drop every grant under the name and expire every request naming it:
 *
 * <ul>
 *   <li><b>Deleted</b> is the obvious one, and it runs before the document
 *       goes, so a failing grant store stops the deletion.</li>
 *   <li><b>Created</b> is the guard against the same hazard arriving from the
 *       other side. Names come back — {@code _trillian-void-*} accounts are
 *       minted and destroyed by the hour, human logins get reused — and any
 *       grant that outlived its owner would be inherited by the new account
 *       without anyone granting it. Clearing on create makes that inheritance
 *       impossible even if a delete once failed, was skipped, or predates this
 *       listener.</li>
 * </ul>
 *
 * <p>That is safe against the seeding paths only because every one of them
 * grants <em>after</em> creating the account ({@code BootstrapBrainService},
 * {@code ProjectLifecycleService}, {@code TrillianSessionBootstrapper}, the
 * anus setup wizard). A future caller that grants ahead of the account would
 * have its seed wiped here — grant after create, always.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimpleAuthUserLifecycleListener implements UserLifecycleListener {

    private final SimpleAuthPermissionBootstrap bootstrap;

    @Override
    public void onUserCreated(UserDocument user) {
        // Idempotent and silent in the normal case: a fresh name holds no
        // grants, so this removes nothing and logs nothing.
        bootstrap.revokeAll(user.getTenantId(), user.getName());
    }

    @Override
    public void onUserDeleted(String tenantId, String name) {
        bootstrap.revokeAll(tenantId, name);
    }
}
