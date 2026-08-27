package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.settings.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Which store account this installation is signed in to, and with what.
 *
 * <p>Two settings per source, because they have two protection classes:
 *
 * <pre>
 * store.account.&lt;sourceId&gt;   visible — shown as "signed in as", and what the licence gate compares
 * store.token.&lt;sourceId&gt;     PASSWORD — the credential itself
 * </pre>
 *
 * <p>Both read through the user → project → tenant cascade. The account
 * belongs to a person, the way an Apple ID belongs to a Mac user rather
 * than to the Mac; putting it at project or tenant level still works and
 * is how a team shares one account, which is the cascade doing its job
 * rather than a special case. Spec: {@code planning/kit-store.md} §3 S3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitStoreCredentials {

    public static final String TOKEN_KEY_PREFIX = "store.token.";
    public static final String ACCOUNT_KEY_PREFIX = "store.account.";

    private final KitSourceRegistry sources;
    private final SettingService settings;

    /**
     * Resolve access for the source covering {@code url}.
     *
     * <p>An explicitly supplied token wins over the configured one: a
     * private git repository needs a credential that has nothing to do
     * with any store, and someone linking a brain for the first time has
     * a token before they have a setting to keep it in.
     *
     * <p>The configured token is only consulted for {@link
     * KitSourceType#LIBRARY}. A {@code store.*} setting standing in as
     * the credential for a git clone would be a surprising thing for the
     * name to mean, and git has always required its token explicitly.
     */
    public KitAccess resolve(
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId,
            @Nullable String url,
            @Nullable String explicitToken) {

        if (url == null || url.isBlank()) {
            return new KitAccess(tenantId, projectId, explicitToken, null, java.util.Map.of(),
                    null, null, userId, true);
        }
        KitSourceDto source;
        try {
            source = sources.resolve(tenantId, url);
        } catch (RuntimeException e) {
            // An unclaimed url is guessed rather than refused, so this is
            // not normally reachable — but resolution is someone else's
            // logic and failing the whole install over a missing credential
            // lookup would be the wrong trade.
            log.debug("KitStoreCredentials: no source for {} — proceeding without settings", url);
            return new KitAccess(tenantId, projectId, explicitToken, null, java.util.Map.of(),
                    null, null, userId, true);
        }

        String account = settings.getStringValueUserProjectCascade(
                tenantId, userId, projectId, null, ACCOUNT_KEY_PREFIX + source.getId());

        String token = explicitToken;
        if ((token == null || token.isBlank()) && source.getType() == KitSourceType.LIBRARY) {
            token = settings.getDecryptedPasswordUserProjectCascade(
                    tenantId, userId, projectId, null, TOKEN_KEY_PREFIX + source.getId());
        }
        // The actor rides along on every path, including the two early
        // returns: a PROJECT source is authorized against the person, and an
        // access object that quietly lost the name would be authorized as
        // SYSTEM instead — allow-by-omission in the one place it must not be.
        return new KitAccess(tenantId, projectId, token, account, java.util.Map.of(),
                null, null, userId, true);
    }
}
