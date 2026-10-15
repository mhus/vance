package de.mhus.vance.brain.sso;

import de.mhus.vance.brain.access.LoginTokenIssuer;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.oauthserver.OAuthSecrets;
import de.mhus.vance.shared.sso.SsoConfig;
import de.mhus.vance.shared.sso.SsoConfigParser;
import de.mhus.vance.shared.sso.SsoConfigService;
import de.mhus.vance.shared.sso.SsoLoginRequestDocument;
import de.mhus.vance.shared.sso.SsoLoginRequestService;
import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.shared.user.ExternalEmails;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The SSO sign-in, start to finish ({@code planning/sso-login.md} §5, §6).
 *
 * <p>Two ways in, never mixed:
 * <ul>
 *   <li><b>Workspace domain</b> — the address's domain is mapped to a tenant
 *       in {@code sso.yaml}. The ID token must carry {@code hd} equal to that
 *       domain: anybody can register a Google account under
 *       {@code someone@acme.com}, only Acme's Workspace issues {@code hd=acme.com}.</li>
 *   <li><b>Private Gmail</b> — no domain mapping ({@code gmail.com} belongs to
 *       everybody). The tenant comes from where the sign-in started, must be
 *       opened for it by the operator, and only accounts prepared for the exact
 *       address get in. The token must carry <em>no</em> {@code hd}.</li>
 * </ul>
 *
 * <p>Every refusal toward the browser is the same generic one; the reason goes
 * to the audit log.
 */
@Service
@Slf4j
public class SsoLoginService {

    public static final String MODE_DOMAIN = "domain";
    public static final String MODE_CONSUMER = "consumer";

    /** Path prefix of every SSO endpoint; also the state cookie's path. */
    public static final String PATH_PREFIX = "/brain/_sso/";

    /** {@code /brain/{tenant}/…} — the tenant a {@code next} path belongs to. */
    private static final Pattern TENANT_PATH = Pattern.compile("^/brain/([^/?#]+)(?:[/?#].*)?$");

    private static final Pattern TENANT_NAME = Pattern.compile("^[A-Za-z0-9_.-]+$");
    private static final Set<String> GOOGLE_ISSUERS = Set.of(SsoConfigParser.GOOGLE_ISSUER, "accounts.google.com");

    /** Failure codes toward the browser — deliberately coarse. */
    public static final String FAIL_REJECTED = "rejected";

    public static final String FAIL_ERROR = "error";

    private final SsoConfigService configService;
    private final SecretResolver secretResolver;
    private final SsoOidcClient oidc;
    private final SsoLoginRequestService requests;
    private final SsoAccountResolver accounts;
    private final LoginTokenIssuer tokenIssuer;
    private final AuditService audit;
    private final @Nullable String publicBaseUrl;
    private final Clock clock;

    @Autowired
    public SsoLoginService(
            SsoConfigService configService,
            SecretResolver secretResolver,
            SsoOidcClient oidc,
            SsoLoginRequestService requests,
            SsoAccountResolver accounts,
            LoginTokenIssuer tokenIssuer,
            AuditService audit,
            @Value("${vance.web.public-base-url:}") String publicBaseUrl) {
        this(
                configService,
                secretResolver,
                oidc,
                requests,
                accounts,
                tokenIssuer,
                audit,
                publicBaseUrl,
                Clock.systemUTC());
    }

    SsoLoginService(
            SsoConfigService configService,
            SecretResolver secretResolver,
            SsoOidcClient oidc,
            SsoLoginRequestService requests,
            SsoAccountResolver accounts,
            LoginTokenIssuer tokenIssuer,
            AuditService audit,
            String publicBaseUrl,
            Clock clock) {
        this.configService = configService;
        this.secretResolver = secretResolver;
        this.oidc = oidc;
        this.requests = requests;
        this.accounts = accounts;
        this.tokenIssuer = tokenIssuer;
        this.audit = audit;
        String trimmed = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.publicBaseUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://") ? trimmed : null;
        this.clock = clock;
    }

    // ─── discovery ─────────────────────────────────────────────────────

    /**
     * Whether an address signs in via SSO — by its domain only. Never names
     * the tenant (that would enumerate tenants over domains), and private
     * Gmail addresses always answer {@code false}: whether one is prepared
     * somewhere is nobody's business.
     */
    public Optional<String> providerForEmail(@Nullable String email) {
        String normalized = ExternalEmails.normalize(email);
        if (normalized == null || publicBaseUrl == null) {
            return Optional.empty();
        }
        return configService.config().domain(ExternalEmails.domain(normalized)).map(SsoConfig.DomainRule::provider);
    }

    /** Whether any e-mail domain signs in via SSO here — not which. */
    public boolean domainSignInConfigured() {
        return publicBaseUrl != null && !configService.config().domains().isEmpty();
    }

    /** Whether the login page of {@code tenant} offers sign-in with a private Google account. */
    public Optional<String> consumerProvider(String tenant) {
        SsoConfig config = configService.config();
        if (publicBaseUrl == null || !config.consumerAccountsAllowed(tenant) || config.consumerAccounts() == null) {
            return Optional.empty();
        }
        return Optional.of(config.consumerAccounts().provider());
    }

    // ─── start ─────────────────────────────────────────────────────────

    /** Outcome of {@link #start}. */
    public sealed interface Start {
        /** Send the browser to {@code url}; {@code state} goes into the state cookie. */
        record Redirect(String url, String state) implements Start {}

        record Failure(String code, @Nullable String tenant) implements Start {}
    }

    /**
     * Opens a sign-in and returns where to send the browser.
     *
     * @param email  typed address (domain sign-in, login hint), or {@code null}
     * @param tenant tenant the login page is bound to, or {@code null}
     * @param next   same-origin path to continue at afterwards, or {@code null}
     */
    public Start start(@Nullable String email, @Nullable String tenant, @Nullable String next) {
        String safeNext = safeNext(next);
        String expectedTenant = validTenant(tenant);
        if (expectedTenant == null && safeNext != null) {
            expectedTenant = tenantOf(safeNext);
        }
        if (publicBaseUrl == null) {
            log.warn("SSO start refused: vance.web.public-base-url is not set — no redirect URI");
            return new Start.Failure(FAIL_ERROR, expectedTenant);
        }
        SsoConfig config = configService.config();
        String normalized = ExternalEmails.normalize(email);

        String mode;
        String providerKey;
        String domain = null;
        if (normalized != null
                && config.domain(ExternalEmails.domain(normalized)).isPresent()) {
            SsoConfig.DomainRule rule =
                    config.domain(ExternalEmails.domain(normalized)).get();
            if (expectedTenant != null && !expectedTenant.equals(rule.tenant())) {
                // The page belongs to one tenant, the address to another: the
                // sign-in must not end in a session the caller did not ask for.
                audit.authSsoRejected(expectedTenant, null, rule.provider(), "tenant-mismatch");
                return new Start.Failure(FAIL_REJECTED, expectedTenant);
            }
            mode = MODE_DOMAIN;
            providerKey = rule.provider();
            domain = rule.domain();
        } else if (expectedTenant != null
                && config.consumerAccountsAllowed(expectedTenant)
                && config.consumerAccounts() != null
                && (normalized == null || ExternalEmails.isGmail(ExternalEmails.domain(normalized)))) {
            mode = MODE_CONSUMER;
            providerKey = config.consumerAccounts().provider();
        } else {
            return new Start.Failure(FAIL_REJECTED, expectedTenant);
        }

        SsoConfig.Provider provider = config.provider(providerKey).orElse(null);
        if (provider == null) {
            return new Start.Failure(FAIL_ERROR, expectedTenant);
        }
        SsoOidcClient.Discovery endpoints;
        try {
            endpoints = oidc.discover(provider);
        } catch (SsoOidcClient.IdentityProviderException e) {
            log.warn("SSO start: discovery of provider '{}' failed: {}", providerKey, e.getMessage());
            return new Start.Failure(FAIL_ERROR, expectedTenant);
        }

        String state = OAuthSecrets.random(32);
        String nonce = OAuthSecrets.random(32);
        String verifier = OAuthSecrets.random(48);
        requests.create(SsoLoginRequestDocument.builder()
                .stateHash(OAuthSecrets.hash(state))
                .nonce(nonce)
                .codeVerifier(verifier)
                .provider(providerKey)
                .mode(mode)
                .domain(domain)
                .expectedTenant(expectedTenant)
                .next(safeNext)
                .build());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", provider.clientId());
        params.put("redirect_uri", redirectUri(providerKey));
        params.put("scope", "openid email profile");
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge", s256(verifier));
        params.put("code_challenge_method", "S256");
        params.put("prompt", "select_account");
        if (domain != null) {
            // Preselection in Google's account chooser only — the check is hd
            // in the ID token.
            params.put("hd", domain);
        }
        if (email != null && !email.isBlank()) {
            params.put("login_hint", email.trim());
        }
        return new Start.Redirect(endpoints.authorizationEndpoint() + "?" + query(params), state);
    }

    // ─── callback ──────────────────────────────────────────────────────

    /** Outcome of {@link #callback}. */
    public sealed interface Callback {
        record Success(UserDocument user, LoginTokenIssuer.Issued issued, String next) implements Callback {}

        record Failure(String code, @Nullable String tenant) implements Callback {}
    }

    /**
     * Completes a sign-in.
     *
     * @param stateCookie the state value from the browser cookie set at start
     * @param error       the provider's {@code error} parameter (user cancelled, …)
     */
    public Callback callback(
            String providerKey,
            @Nullable String code,
            @Nullable String state,
            @Nullable String error,
            @Nullable String stateCookie) {
        if (state == null || state.isBlank()) {
            return new Callback.Failure(FAIL_ERROR, null);
        }
        // Login-CSRF: the callback must come from the browser that started it.
        if (stateCookie == null || !OAuthSecrets.sameHash(OAuthSecrets.hash(state), OAuthSecrets.hash(stateCookie))) {
            log.debug("SSO callback refused: state cookie missing or different");
            return new Callback.Failure(FAIL_ERROR, null);
        }
        SsoLoginRequestDocument request =
                requests.take(OAuthSecrets.hash(state)).orElse(null);
        if (request == null) {
            return new Callback.Failure(FAIL_ERROR, null);
        }
        String tenantHint = request.getExpectedTenant();
        if (!providerKey.equals(request.getProvider())) {
            return new Callback.Failure(FAIL_ERROR, tenantHint);
        }
        if (error != null || code == null || code.isBlank()) {
            log.debug("SSO callback: provider returned error='{}'", error);
            return new Callback.Failure(FAIL_ERROR, tenantHint);
        }

        SsoConfig config = configService.config();
        SsoConfig.Provider provider = config.provider(providerKey).orElse(null);
        String clientSecret = provider == null ? null : clientSecret(provider);
        if (provider == null || clientSecret == null) {
            log.error("SSO callback: provider '{}' missing or its client secret does not resolve", providerKey);
            return new Callback.Failure(FAIL_ERROR, tenantHint);
        }

        IdTokenVerifier.IdToken token;
        try {
            SsoOidcClient.Discovery endpoints = oidc.discover(provider);
            String idToken = oidc.exchangeCode(
                    endpoints,
                    provider.clientId(),
                    clientSecret,
                    code,
                    redirectUri(providerKey),
                    request.getCodeVerifier());
            token = IdTokenVerifier.verify(
                    idToken,
                    kid -> keyOrNull(endpoints, kid),
                    issuersOf(provider),
                    provider.clientId(),
                    request.getNonce(),
                    clock);
        } catch (SsoOidcClient.IdentityProviderException e) {
            log.warn("SSO callback: identity provider '{}' failed: {}", providerKey, e.getMessage());
            return new Callback.Failure(FAIL_ERROR, tenantHint);
        } catch (IdTokenVerifier.InvalidIdTokenException e) {
            log.warn("SSO callback: ID token from '{}' rejected: {}", providerKey, e.getMessage());
            audit.authSsoRejected(tenantHint, null, providerKey, "id-token-invalid");
            return new Callback.Failure(FAIL_REJECTED, tenantHint);
        }

        Policy policy = policy(request, config, token);
        if (policy.rejection() != null) {
            log.info(
                    "SSO sign-in refused provider='{}' tenant='{}': {}",
                    providerKey,
                    policy.tenant(),
                    policy.rejection());
            audit.authSsoRejected(policy.tenant(), null, providerKey, policy.rejection());
            return new Callback.Failure(FAIL_REJECTED, tenantHint);
        }
        String tenant = policy.tenant();
        String normalized = policy.normalizedEmail();
        if (tenant == null || normalized == null || token.email() == null) {
            return new Callback.Failure(FAIL_REJECTED, tenantHint);
        }

        SsoAccountResolver.Outcome outcome = accounts.resolve(new SsoAccountResolver.SignIn(
                tenant,
                providerKey,
                token.issuer(),
                token.subject(),
                token.email(),
                normalized,
                SsoAccountResolver.nameFor(normalized),
                policy.autoCreate()));
        if (outcome.user() == null) {
            log.info("SSO sign-in refused provider='{}' tenant='{}': {}", providerKey, tenant, outcome.rejection());
            audit.authSsoRejected(tenant, null, providerKey, String.valueOf(outcome.rejection()));
            return new Callback.Failure(FAIL_REJECTED, tenantHint);
        }
        UserDocument user = outcome.user();

        Instant sessionEnd = clock.instant().plus(policy.maxSession());
        LoginTokenIssuer.Issued issued = tokenIssuer.issue(tenant, user.getName(), true, sessionEnd);
        audit.authSsoLoginSuccess(tenant, user.getName(), providerKey, token.issuer(), request.getMode());
        String next = request.getNext() == null ? "/" : request.getNext();
        return new Callback.Success(user, issued, next);
    }

    /** The rule-level verdict on a verified token, before any account is looked at. */
    private record Policy(
            @Nullable String tenant,
            @Nullable String normalizedEmail,
            boolean autoCreate,
            Duration maxSession,
            @Nullable String rejection) {

        static Policy reject(@Nullable String tenant, String reason) {
            return new Policy(tenant, null, false, Duration.ZERO, reason);
        }
    }

    private Policy policy(SsoLoginRequestDocument request, SsoConfig config, IdTokenVerifier.IdToken token) {
        String expectedTenant = request.getExpectedTenant();
        if (!token.emailVerified()) {
            return Policy.reject(expectedTenant, "email-not-verified");
        }
        String normalized = ExternalEmails.normalize(token.email());
        if (normalized == null || ExternalEmails.hasPlusTag(normalized)) {
            return Policy.reject(expectedTenant, "email-unusable");
        }
        String emailDomain = ExternalEmails.domain(normalized);

        if (MODE_DOMAIN.equals(request.getMode())) {
            String domain = request.getDomain();
            SsoConfig.DomainRule rule =
                    domain == null ? null : config.domain(domain).orElse(null);
            if (rule == null) {
                return Policy.reject(expectedTenant, "domain-not-configured");
            }
            // hd is the protection: only the domain's own Workspace sets it.
            if (token.hostedDomain() == null || !domain.equalsIgnoreCase(token.hostedDomain())) {
                return Policy.reject(rule.tenant(), "hosted-domain-mismatch");
            }
            if (!domain.equals(emailDomain)) {
                return Policy.reject(rule.tenant(), "email-domain-mismatch");
            }
            if (expectedTenant != null && !expectedTenant.equals(rule.tenant())) {
                return Policy.reject(expectedTenant, "tenant-mismatch");
            }
            if (!rule.allows(ExternalEmails.localPart(normalized))) {
                return Policy.reject(rule.tenant(), "not-allowed");
            }
            return new Policy(rule.tenant(), normalized, rule.autoCreate(), rule.maxSession(), null);
        }

        if (MODE_CONSUMER.equals(request.getMode())) {
            if (expectedTenant == null
                    || !config.consumerAccountsAllowed(expectedTenant)
                    || config.consumerAccounts() == null) {
                return Policy.reject(expectedTenant, "consumer-not-allowed");
            }
            // An account with hd belongs to a Workspace and takes the domain
            // path; an account without hd under a foreign domain is exactly the
            // forgery hd protects against.
            if (token.hostedDomain() != null) {
                return Policy.reject(expectedTenant, "workspace-account-on-consumer-path");
            }
            if (!ExternalEmails.GMAIL.equals(emailDomain)) {
                return Policy.reject(expectedTenant, "not-a-gmail-address");
            }
            return new Policy(
                    expectedTenant, normalized, false, config.consumerAccounts().maxSession(), null);
        }
        return Policy.reject(expectedTenant, "unknown-mode");
    }

    // ─── helpers ───────────────────────────────────────────────────────

    /**
     * The provider's client secret, resolved from its reference in the system
     * tenant — or {@code null} when it does not resolve. Read at use, never
     * cached, never logged.
     */
    private @Nullable String clientSecret(SsoConfig.Provider provider) {
        String resolved = secretResolver.resolveForConnector(
                provider.clientSecret(),
                new ToolInvocationContext(
                        TenantService.SYSTEM_TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, null, null, null));
        return resolved == null || resolved.isBlank() ? null : resolved;
    }

    /** Redirect URI registered at the provider for {@code providerKey}. */
    public String redirectUri(String providerKey) {
        return publicBaseUrl + PATH_PREFIX + providerKey + "/callback";
    }

    private @Nullable Key keyOrNull(SsoOidcClient.Discovery endpoints, String kid) {
        try {
            return oidc.publicKey(endpoints, kid);
        } catch (SsoOidcClient.IdentityProviderException e) {
            log.warn("SSO: key set unavailable: {}", e.getMessage());
            return null;
        }
    }

    private static Set<String> issuersOf(SsoConfig.Provider provider) {
        return SsoConfigParser.GOOGLE_ISSUER.equals(provider.issuer()) ? GOOGLE_ISSUERS : Set.of(provider.issuer());
    }

    /**
     * A same-origin path, or {@code null}. Same rule as the login page's
     * {@code readNextParam}: starts with one slash, no scheme-relative
     * {@code //host}, no backslashes or control characters.
     */
    static @Nullable String safeNext(@Nullable String next) {
        if (next == null || next.isBlank()) {
            return null;
        }
        if (!next.startsWith("/") || next.startsWith("//") || next.contains("\\")) {
            return null;
        }
        for (int i = 0; i < next.length(); i++) {
            char c = next.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return null;
            }
        }
        return next;
    }

    static @Nullable String tenantOf(String path) {
        Matcher m = TENANT_PATH.matcher(path);
        if (!m.matches()) {
            return null;
        }
        String tenant = m.group(1);
        return tenant.startsWith("_") ? null : validTenant(tenant);
    }

    private static @Nullable String validTenant(@Nullable String tenant) {
        return tenant != null && TENANT_NAME.matcher(tenant).matches() ? tenant : null;
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String query(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
