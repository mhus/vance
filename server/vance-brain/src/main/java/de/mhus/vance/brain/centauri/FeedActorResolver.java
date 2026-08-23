package de.mhus.vance.brain.centauri;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.feed.FeedActor;
import de.mhus.vance.toolpack.feed.FeedScope;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
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
 * <p><b>The pseudonym is per endpoint</b>, and that is the actual decision
 * rather than decoration: one derivation shared across sources would let two
 * of them join their profiles over the same reader. Per endpoint,
 * cross-source correlation is impossible while the reader stays recognisable
 * within one source — exactly as much as the feature needs.
 *
 * <p>Two ingredients carry that property, and they answer different halves of
 * it. The <b>salt</b> is stored per endpoint name and is what makes the
 * pseudonym unguessable and rotatable. The endpoint's <b>base URL</b> travels
 * in the HMAC message, and that is what makes the derivation specific to the
 * service rather than to a local label: {@code centauri.endpoint.news} in two
 * projects is two different foreign organisations under one name, and the
 * salt lives tenant-wide, so without the URL both would see the same reader
 * under the same pseudonym. Operational consequence: rotating the salt — or
 * repointing an endpoint at another host — makes every reader look new to
 * that source.
 */
@Service
@Slf4j
public class FeedActorResolver {

    /** Length of the base64url pseudonym. 128 bits of the digest is plenty. */
    static final int PSEUDONYM_LENGTH = 22;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SALT_BYTES = 32;

    /**
     * How long a resolved salt is held in this pod.
     *
     * <p>Matches {@link FeedSourceFactory#DEFAULT_TTL} so a rotated salt takes
     * effect in the same window as a rotated credential — an in-process cache
     * that never expires would make rotation a restart.
     */
    private static final Duration SALT_CACHE_TTL = Duration.ofMinutes(5);

    private final SettingService settings;
    private final SecretResolver secretResolver;
    private final SecureRandom random = new SecureRandom();

    /**
     * Resolved salts per {@code (tenant, project, endpoint)}.
     *
     * <p>Not an optimisation. {@code resolve} runs on one virtual thread per
     * stream, so a source with four selectors reaches the generation branch
     * four times at once; each would mint its own salt and overwrite the
     * others, and the streams of a single page would then travel under
     * different pseudonyms. Caffeine's loader is atomic per key, so exactly
     * one of them generates. It also keeps a permanently failing write from
     * costing a fresh 32-byte secret and a write attempt on every stream of
     * every page in what is an {@code Action.READ} path — a {@code null}
     * loader result is not cached, but the attempt is at least serialised.
     *
     * <p>Keyed by project as well as tenant even though the salt is written
     * tenant-wide: the read goes through the project cascade, so a project
     * may override it, and a tenant-only key would hand that override to its
     * neighbours.
     */
    private final Cache<String, String> saltCache = Caffeine.newBuilder()
            .maximumSize(256)
            .expireAfterWrite(SALT_CACHE_TTL)
            .build();

    public FeedActorResolver(SettingService settings, SecretResolver secretResolver) {
        this.settings = settings;
        this.secretResolver = secretResolver;
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
     *
     * <p>Takes the instance rather than its id because the derivation needs
     * the endpoint's identity, and the id is only its local name.
     */
    public @Nullable FeedActor resolve(FeedScope scope, FeedSourceInstance instance) {
        String userId = scope.userId();
        if (StringUtils.isBlank(userId)) {
            return null;
        }
        String sourceId = instance.id();
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
        return new FeedActor(
                pseudonym(salt, scope.tenantId(), userId, endpointIdentity(instance)));
    }

    /**
     * HMAC over {@code tenantId}, {@code userId} and the endpoint identity
     * with the instance salt. Every part is length-prefixed so no triple of
     * ids can produce the same input by concatenation.
     */
    static String pseudonym(String salt, String tenantId, String userId, String endpoint) {
        String message = prefixed(tenantId) + '|' + prefixed(userId) + '|' + prefixed(endpoint);
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
     * What distinguishes one foreign service from another: its base URL, or
     * its id when the protocol works without one. Never the id alone — that
     * is a local label two projects can pick for two different services.
     */
    private static String endpointIdentity(FeedSourceInstance instance) {
        String baseUrl = instance.baseUrl();
        return StringUtils.isBlank(baseUrl) ? instance.id() : baseUrl.trim();
    }

    private static String prefixed(String part) {
        return part.length() + ":" + part;
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
     * distributed lock. Within one pod the cache loader settles it outright.
     */
    private @Nullable String obtainSalt(FeedScope scope, String sourceId) {
        String cacheKey = StringUtils.defaultString(scope.tenantId()) + '/'
                + StringUtils.defaultString(scope.projectId()) + '/' + sourceId;
        return saltCache.get(cacheKey, k -> loadOrGenerateSalt(scope, sourceId));
    }

    private @Nullable String loadOrGenerateSalt(FeedScope scope, String sourceId) {
        String key = CentauriSettings.endpointActorSaltKey(sourceId);
        String existing = readSalt(scope, key);
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
        String reread = readSalt(scope, key);
        return StringUtils.isNotBlank(reread) ? reread : generated;
    }

    /**
     * The stored salt, with {@code {{secret:…}}} references resolved.
     *
     * <p>Through {@code resolveForConnector} rather than {@code resolve}: a
     * feed protocol is a connector, so the value may live in a {@code
     * PASSWORD}-typed setting or a vault (spec §10). Without this an operator
     * who pointed the salt at their secret manager would have signed every
     * fetch with the literal reference text.
     *
     * <p>The invocation context deliberately carries no user: the salt belongs
     * to the endpoint, not to whoever is reading right now, and a user-scoped
     * reference would hand each reader their own salt — which is the same as
     * having no shared pseudonym at all.
     */
    private @Nullable String readSalt(FeedScope scope, String key) {
        String raw = settings.getDecryptedPasswordCascade(
                scope.tenantId(), scope.projectId(), null, key);
        if (raw == null) {
            return null;
        }
        return secretResolver.resolveForConnector(raw, new ToolInvocationContext(
                scope.tenantId(), scope.projectId(), null, null, null));
    }
}
