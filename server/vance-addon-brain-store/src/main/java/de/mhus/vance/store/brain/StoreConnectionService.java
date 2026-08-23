package de.mhus.vance.store.brain;

import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.kit.KitStoreCredentials;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.settings.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Signing a brain user in to a store, and out again.
 *
 * <p>The whole of signing in is: use the password once to obtain a
 * session, mint a link with it, keep the <b>link</b> token and throw the
 * session away. The brain never holds a store session — a session is a
 * person being present, and a brain is not a person.
 *
 * <p>What it keeps instead is two settings on the signing-in user, which
 * is where an Apple ID sits on a Mac: on the account that signed in, not
 * on the machine.
 *
 * <p>Spec: {@code planning/kit-store.md} §3 S3, §7 Phase S3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoreConnectionService {

    private final StoreClient client;
    private final SettingService settings;

    /** Who this installation is signed in to for one source, if anyone. */
    public record Connection(String sourceId, @Nullable String accountId) {

        public boolean isConnected() {
            return accountId != null && !accountId.isBlank();
        }
    }

    /**
     * Which account this installation is signed in to for one source.
     *
     * <p>{@code projectId} matters: the cascade has a project layer, and it
     * is how a team shares one account. Reading without it said "not signed
     * in" for exactly that setup — while installing and reviewing on the
     * same screen resolved the credential fine, because
     * {@code KitStoreCredentials} did pass it. One cascade, asked the same
     * way from both ends.
     */
    public Connection connectionOf(
            String tenantId, String userId, @Nullable String projectId, KitSourceDto source) {
        return new Connection(source.getId(), settings.getStringValueUserProjectCascade(
                tenantId, userId, projectId, null, accountKey(source)));
    }

    /**
     * Sign in and keep the resulting link.
     *
     * <p>The token is written as {@link SettingType#PASSWORD}: compiled
     * server code reads it — the kit installer and the library listing —
     * and dynamic elements such as agents and scripts have no business
     * with it. The account id is written in the clear beside it, because
     * it is not a secret and both the licence gate and the screen need it.
     *
     * <p>{@code PASSWORD} carries only half of that on its own. It keeps an
     * agent from <em>reading</em> the value, but a connector may resolve a
     * {@code PASSWORD} through {@code {{secret:…}}} by design — so
     * {@code store.*} has to be in <b>both</b> operator deny lists:
     * {@code vance.settings.agentWriteDenyKeys} (an agent must not rewrite
     * whose account this brain buys on) and
     * {@code vance.settings.secretReferenceDenyKeys} (a tool document must
     * not be able to put the link token in a header pointing anywhere).
     * Both live in {@code application.yml}; see
     * {@code SecretReferenceKeyPolicy} for why the type is not the guard.
     */
    public Connection connect(
            String tenantId, String userId, KitSourceDto source,
            String email, String password, @Nullable String label, @Nullable String projectId) {

        StoreClient.Session session = client.login(source, email, password);
        StoreClient.IssuedLink link;
        try {
            link = client.createLink(source, session, label, tenantId, projectId);
        } finally {
            // Whether or not the link was minted, the session has done its
            // job. Leaving it open would be a live credential for this
            // account that nobody is holding on purpose.
            client.logout(source, session);
        }

        String scope = userScope(userId);
        settings.setEncryptedSecret(tenantId, SettingService.SCOPE_PROJECT, scope,
                tokenKey(source), link.token(), SettingType.PASSWORD);
        settings.setStringValue(tenantId, SettingService.SCOPE_PROJECT, scope,
                accountKey(source), session.accountId());

        log.info("StoreConnectionService: user '{}' signed in to store '{}' as {} (link {})",
                userId, source.getId(), session.accountId(), link.linkId());
        return new Connection(source.getId(), session.accountId());
    }

    /**
     * Sign out here.
     *
     * <p>Removes the two settings and nothing else. The link at the store
     * survives, deliberately: revoking it is a decision about a device
     * list, and it belongs on the store's own screen where the person can
     * see which entry they are killing. Forgetting a credential locally
     * and deauthorising a machine remotely are different acts, and doing
     * the second silently as a side effect of the first would be a
     * surprise nobody asked for.
     */
    public void disconnect(String tenantId, String userId, KitSourceDto source) {
        String scope = userScope(userId);
        settings.delete(tenantId, SettingService.SCOPE_PROJECT, scope, tokenKey(source));
        settings.delete(tenantId, SettingService.SCOPE_PROJECT, scope, accountKey(source));
        log.info("StoreConnectionService: user '{}' signed out of store '{}'",
                userId, source.getId());
    }

    /**
     * The settings scope of one user.
     *
     * <p>A user's own hub project — which is how the settings cascade
     * expresses "belongs to this person" and why the token lands there
     * rather than on the project being worked in.
     */
    private static String userScope(String userId) {
        if (userId.isBlank()) {
            throw new KitException("signing in to a store needs a user — a brain cannot"
                    + " hold a store account on nobody's behalf");
        }
        return HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId;
    }

    private static String tokenKey(KitSourceDto source) {
        return KitStoreCredentials.TOKEN_KEY_PREFIX + source.getId();
    }

    private static String accountKey(KitSourceDto source) {
        return KitStoreCredentials.ACCOUNT_KEY_PREFIX + source.getId();
    }
}
