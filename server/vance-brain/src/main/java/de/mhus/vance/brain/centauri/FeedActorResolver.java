package de.mhus.vance.brain.centauri;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.feed.FeedActor;
import de.mhus.vance.toolpack.feed.FeedScope;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Derives the reader pseudonym a source is allowed to see.
 *
 * <p>Its only purpose is to keep a reader-specific view possible —
 * personalised selection, source-side read marks, language preference —
 * without a source ever learning who the reader is. No login, no mail,
 * nothing reversible.
 *
 * <p>Derivation is central rather than per protocol for a plain reason: with
 * three protocols there would be three chances to get the salting wrong, and
 * the {@code sendActor} switch would have to be honoured in three places
 * instead of one.
 *
 * <p><b>The salt is per instance</b>, and that is the actual decision rather
 * than decoration: a shared salt would let two sources join their profiles
 * over the same reader. Per instance, cross-source correlation is impossible
 * while the reader stays recognisable within one source — exactly as much as
 * the feature needs. The operational consequence: rotating the salt makes
 * every reader look new to that source.
 */
@Service
@Slf4j
public class FeedActorResolver {

    /** Length of the base64url pseudonym. 128 bits of the digest is plenty. */
    static final int PSEUDONYM_LENGTH = 22;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SALT_BYTES = 32;

    private final SettingService settings;
    private final SecureRandom random = new SecureRandom();

    public FeedActorResolver(SettingService settings) {
        this.settings = settings;
    }

    /**
     * The pseudonym for this reader at this source, or {@code null} for an
     * anonymous call.
     *
     * <p>Null is a supported answer in three cases, and all three are normal:
     * no user in scope (scheduler, service account), {@code sendActor=false}
     * on the endpoint, or no salt obtainable. Every source has to answer a
     * fetch without a pseudonym anyway — a source that needs one to respond
     * at all breaks every digest job.
     */
    public @Nullable FeedActor resolve(FeedScope scope, String sourceId) {
        String userId = scope.userId();
        if (StringUtils.isBlank(userId)) {
            return null;
        }
        if (!settings.getBooleanValueCascade(scope.tenantId(), scope.projectId(), null,
                CentauriSettings.endpointSendActorKey(sourceId), true)) {
            return null;
        }
        String salt = obtainSalt(scope, sourceId);
        if (salt == null) {
            log.warn("Centauri: no actor salt for source '{}' in tenant '{}' — "
                    + "sending the fetch anonymously", sourceId, scope.tenantId());
            return null;
        }
        return new FeedActor(pseudonym(salt, scope.tenantId(), userId));
    }

    /**
     * HMAC over {@code tenantId} and {@code userId} with the instance salt.
     * Both parts are length-prefixed so no pair of ids can produce the same
     * input by concatenation.
     */
    static String pseudonym(String salt, String tenantId, String userId) {
        String message = tenantId.length() + ":" + tenantId + "|" + userId.length() + ":" + userId;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            String full = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return full.substring(0, Math.min(PSEUDONYM_LENGTH, full.length()));
        } catch (java.security.GeneralSecurityException e) {
            throw new CentauriException("could not derive feed actor pseudonym", e);
        }
    }

    /**
     * Read the instance salt, generating it once when absent.
     *
     * <p>Written at the tenant-wide project scope, because that is where feed
     * endpoints normally live and a salt below it would silently give the same
     * reader two identities in two projects of the same tenant.
     *
     * <p>Two pods generating at the same time is possible; the loser re-reads
     * and adopts the winner's value. A genuine race therefore costs at most
     * one salt rotation for that source, which is why this does not warrant a
     * lock.
     */
    private @Nullable String obtainSalt(FeedScope scope, String sourceId) {
        String key = CentauriSettings.endpointActorSaltKey(sourceId);
        String existing = settings.getDecryptedPasswordCascade(
                scope.tenantId(), scope.projectId(), null, key);
        if (StringUtils.isNotBlank(existing)) {
            return existing;
        }
        byte[] fresh = new byte[SALT_BYTES];
        random.nextBytes(fresh);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(fresh);
        try {
            settings.setEncryptedSecret(
                    scope.tenantId(), SettingService.SCOPE_PROJECT,
                    HomeBootstrapService.TENANT_PROJECT_NAME, key,
                    generated, SettingType.PASSWORD);
            log.info("Centauri: generated actor salt for source '{}' in tenant '{}'",
                    sourceId, scope.tenantId());
        } catch (RuntimeException e) {
            log.warn("Centauri: could not persist actor salt for source '{}': {}",
                    sourceId, e.toString());
            return null;
        }
        String reread = settings.getDecryptedPasswordCascade(
                scope.tenantId(), scope.projectId(), null, key);
        return StringUtils.isNotBlank(reread) ? reread : generated;
    }
}
