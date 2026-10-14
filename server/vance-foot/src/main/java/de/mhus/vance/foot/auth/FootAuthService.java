package de.mhus.vance.foot.auth;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.foot.config.FootConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Owns credential acquisition for the foot client.
 *
 * <p><b>Connect-time cascade</b> ({@link #acquireAccessToken()}):
 * <ol>
 *   <li>stored access token still valid → use it (no network);</li>
 *   <li>stored refresh token still valid → re-mint (rolling refresh) and
 *       persist the new tokens;</li>
 *   <li>a {@code vance.auth.password} is configured → mint with it, but do
 *       <em>not</em> persist (zero-config dev login, unchanged from before —
 *       running foot in an arbitrary directory must not litter it with a
 *       token file);</li>
 *   <li>otherwise fail with {@link AuthRequiredException} pointing at
 *       {@code /login}.</li>
 * </ol>
 *
 * <p><b>Interactive login</b> ({@link #login(LoginRequest)}) always mints
 * with a password, requests a refresh token, writes both
 * {@code access.yaml} (secret) and {@code project.eddie.yaml} (binding) to the
 * login target directory, and applies the binding to the running config.
 *
 * <p><b>Browser login</b> ({@link #loginWithBrowser}) gets the tokens from the
 * brain's OAuth server instead ({@link OAuthBrowserLogin}); the stored
 * {@code authMethod: oauth} makes step 2 of the cascade refresh at the OAuth
 * token endpoint, and {@link #logout()} revokes the grant there.
 *
 * <p><b>Concurrency:</b> every read-modify-write of {@code access.yaml}
 * (refresh, login, logout) runs under {@link AccessStore#withLock}, and a
 * refresh re-reads the file once it holds the lock — another foot in the same
 * directory may have rotated the refresh token in the meantime, and using the
 * old one again would look like a replay to the OAuth server.
 */
@Service
@Slf4j
public class FootAuthService {

    /** Treat a token as expired this many millis before its real expiry. */
    private static final long EXPIRY_SKEW_MS = 60_000L;

    private final FootConfig config;
    private final VancePaths paths;
    private final AccessStore accessStore;
    private final ProjectBindingStore bindingStore;
    private final ProjectBindingApplier bindingApplier;
    private final AccessTokenClient client;
    private final OAuthClient oauthClient;
    private final OAuthBrowserLogin browserLogin;
    private final LongSupplier clock;

    /** {@link AccessData#getAuthMethod()} of a browser (OAuth) login. */
    static final String AUTH_METHOD_OAUTH = "oauth";

    /**
     * Outcome of {@link #logout()}.
     *
     * @param removed            a credential file was deleted
     * @param serverRevokeFailed a browser login was to be revoked at the
     *                           server, but the server did not confirm it — the
     *                           grant may still be live there
     */
    public record LogoutResult(boolean removed, boolean serverRevokeFailed) {}

    @Autowired
    public FootAuthService(
            FootConfig config,
            VancePaths paths,
            AccessStore accessStore,
            ProjectBindingStore bindingStore,
            ProjectBindingApplier bindingApplier,
            AccessTokenClient client,
            OAuthClient oauthClient,
            OAuthBrowserLogin browserLogin) {
        this(
                config,
                paths,
                accessStore,
                bindingStore,
                bindingApplier,
                client,
                oauthClient,
                browserLogin,
                System::currentTimeMillis);
    }

    /** Test constructor with an injectable clock. */
    FootAuthService(
            FootConfig config,
            VancePaths paths,
            AccessStore accessStore,
            ProjectBindingStore bindingStore,
            ProjectBindingApplier bindingApplier,
            AccessTokenClient client,
            OAuthClient oauthClient,
            OAuthBrowserLogin browserLogin,
            LongSupplier clock) {
        this.config = config;
        this.paths = paths;
        this.accessStore = accessStore;
        this.bindingStore = bindingStore;
        this.bindingApplier = bindingApplier;
        this.client = client;
        this.oauthClient = oauthClient;
        this.browserLogin = browserLogin;
        this.clock = clock;
    }

    /**
     * Resolves a usable access token following the connect-time cascade.
     * Persists refreshed tokens back to the directory they were read from.
     */
    public AccessTokenResponse acquireAccessToken() throws Exception {
        Path dir = paths.activeDir();
        Optional<AccessData> storedOpt = accessStore.load(dir);

        if (storedOpt.isPresent()) {
            AccessData stored = storedOpt.get();
            if (isValid(stored.getAccessToken(), stored.getAccessExpiresAt())) {
                log.debug("using cached access token from {}", accessStore.file(dir));
                return toResponse(stored.getAccessToken(), stored.getAccessExpiresAt());
            }
            AccessTokenResponse refreshed = accessStore.withLock(dir, () -> refreshLocked(dir));
            if (refreshed != null) {
                return refreshed;
            }
        }

        String password = config.getAuth().getPassword();
        if (password != null && !password.isBlank()) {
            log.debug("minting access token via configured password (not persisted)");
            return client.mint(
                    config.getBrain().getHttpBase(),
                    config.getAuth().getTenant(),
                    config.getAuth().getUsername(),
                    AccessTokenRequest.builder().password(password).build());
        }

        throw new AuthRequiredException(
                "No valid stored credentials — run /login (or set vance.auth.password for a dev login).");
    }

    /**
     * Steps 1–2 of the cascade, run while holding the access lock: the file is
     * read again, because another client may have refreshed while this one
     * waited — then its fresh access token is used and the (already rotated)
     * refresh token is not touched.
     *
     * @return the token, or {@code null} when the stored credentials are gone
     *         or fully expired (the caller falls through to step 3)
     */
    private @Nullable AccessTokenResponse refreshLocked(Path dir) throws Exception {
        Optional<AccessData> current = accessStore.load(dir);
        if (current.isEmpty()) {
            return null;
        }
        AccessData stored = current.get();
        if (isValid(stored.getAccessToken(), stored.getAccessExpiresAt())) {
            log.debug("access token in {} was refreshed by another client meanwhile", accessStore.file(dir));
            return toResponse(stored.getAccessToken(), stored.getAccessExpiresAt());
        }
        if (AUTH_METHOD_OAUTH.equals(stored.getAuthMethod())) {
            return refreshOAuth(dir, stored);
        }
        if (isValid(stored.getRefreshToken(), stored.getRefreshExpiresAt())) {
            String username =
                    firstNonBlank(stored.getUsername(), config.getAuth().getUsername());
            log.info("access token expired — refreshing via stored refresh token (user='{}')", username);
            AccessTokenResponse minted = client.mint(
                    config.getBrain().getHttpBase(),
                    config.getAuth().getTenant(),
                    username,
                    AccessTokenRequest.builder()
                            .refreshToken(stored.getRefreshToken())
                            .requestRefreshToken(true)
                            .build());
            persist(dir, username, minted, stored);
            return minted;
        }
        log.debug("stored credentials in {} are fully expired", accessStore.file(dir));
        return null;
    }

    /**
     * Performs an interactive password login: mints with a refresh token,
     * writes {@code access.yaml} + {@code project.eddie.yaml} to the login target
     * directory, and overlays the binding onto the running config.
     */
    public LoginResult login(LoginRequest request) throws Exception {
        TransportGuard.assertAllowed(
                request.httpBase(),
                request.wsBase(),
                config.getBrain().isAllowInsecureTransport(),
                true,
                msg -> log.warn("{}", msg));

        AccessTokenResponse minted = client.mint(
                request.httpBase(),
                request.tenant(),
                request.username(),
                AccessTokenRequest.builder()
                        .password(request.password())
                        .requestRefreshToken(true)
                        .build());

        Path dir = paths.loginTargetDir();
        AccessData data = new AccessData();
        data.setUsername(request.username());
        data.setAccessToken(minted.getToken());
        data.setAccessExpiresAt(minted.getExpiresAtTimestamp());
        data.setRefreshToken(minted.getRefreshToken());
        data.setRefreshExpiresAt(minted.getRefreshTokenExpiresAtTimestamp());
        replaceStored(dir, data);

        ProjectBinding binding = buildBinding(request);
        bindingStore.save(dir, binding);
        bindingApplier.apply(binding, config);

        log.info(
                "login succeeded: user='{}' tenant='{}' project='{}' dir={}",
                request.username(),
                request.tenant(),
                request.project(),
                dir);
        return new LoginResult(dir, minted, binding);
    }

    /**
     * Browser login against the OAuth server: the user signs in in their
     * browser, the CLI receives tokens of the {@code account} scope. Writes
     * {@code access.yaml} (with {@code authMethod: oauth}) and the binding like
     * {@link #login(LoginRequest)} does.
     *
     * @param showUrl receives the URL to open, in case no browser starts by itself
     */
    public LoginResult loginWithBrowser(
            String httpBase,
            String wsBase,
            String tenant,
            @Nullable String project,
            Consumer<String> showUrl,
            Duration timeout)
            throws Exception {
        TransportGuard.assertAllowed(
                httpBase, wsBase, config.getBrain().isAllowInsecureTransport(), true, msg -> log.warn("{}", msg));

        OAuthBrowserLogin.Result result =
                browserLogin.run(httpBase, tenant, config.getBrain().isAllowInsecureTransport(), showUrl, timeout);
        long now = clock.getAsLong();
        Path dir = paths.loginTargetDir();
        AccessData data = new AccessData();
        data.setUsername(result.username());
        data.setAccessToken(result.tokens().accessToken());
        data.setAccessExpiresAt(now + result.tokens().expiresIn() * 1000L);
        data.setRefreshToken(result.tokens().refreshToken());
        data.setAuthMethod(AUTH_METHOD_OAUTH);
        data.setOauthTokenEndpoint(result.endpoints().tokenEndpoint());
        data.setOauthRevocationEndpoint(result.endpoints().revocationEndpoint());
        data.setOauthClientId(OAuthBrowserLogin.CLIENT_ID);
        replaceStored(dir, data);

        ProjectBinding binding =
                buildBinding(new LoginRequest(httpBase, wsBase, tenant, result.username(), project, ""));
        bindingStore.save(dir, binding);
        bindingApplier.apply(binding, config);

        log.info(
                "browser login succeeded: user='{}' tenant='{}' project='{}' dir={}",
                result.username(),
                tenant,
                project,
                dir);
        AccessTokenResponse token = toResponse(data.getAccessToken(), data.getAccessExpiresAt());
        return new LoginResult(dir, token, binding);
    }

    /**
     * Writes a fresh login to {@code dir} under the access lock. A browser
     * login it replaces is revoked at its OAuth server afterwards (best
     * effort) — the server issues one grant per login, and the old one would
     * otherwise stay live, unreachable from this CLI, until it expires.
     */
    private void replaceStored(Path dir, AccessData data) throws Exception {
        Optional<AccessData> previous = accessStore.withLock(dir, () -> {
            Optional<AccessData> old = loadQuietly(dir);
            accessStore.save(dir, data);
            return old;
        });
        previous.ifPresent(old -> revokeReplaced(old, data));
    }

    private void revokeReplaced(AccessData old, AccessData replacement) {
        String oldRefresh = old.getRefreshToken();
        String endpoint = old.getOauthRevocationEndpoint();
        if (!AUTH_METHOD_OAUTH.equals(old.getAuthMethod())
                || oldRefresh == null
                || oldRefresh.isBlank()
                || endpoint == null
                || oldRefresh.equals(replacement.getRefreshToken())) {
            return;
        }
        if (oauthClient.revoke(endpoint, clientIdOf(old), oldRefresh)) {
            log.info("revoked the browser login replaced by this login");
        } else {
            log.warn("could not revoke the browser login replaced by this login — "
                    + "it may still be listed under the connected apps of the account");
        }
    }

    /** A broken previous file must not block a new login — it is overwritten anyway. */
    private Optional<AccessData> loadQuietly(Path dir) {
        try {
            return accessStore.load(dir);
        } catch (AccessStoreException e) {
            log.debug("ignoring unreadable previous access file: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String clientIdOf(AccessData data) {
        return data.getOauthClientId() == null ? OAuthBrowserLogin.CLIENT_ID : data.getOauthClientId();
    }

    /**
     * Step 2 of the cascade for a browser login: rotate the refresh token at
     * the OAuth token endpoint. The server owns the refresh token's lifetime
     * (sliding), so there is no local expiry to check — a refusal means the
     * login is gone and the user has to sign in again. Runs under the access
     * lock (see {@link #refreshLocked}).
     */
    private AccessTokenResponse refreshOAuth(Path dir, AccessData stored) throws Exception {
        String refreshToken = stored.getRefreshToken();
        String tokenEndpoint = stored.getOauthTokenEndpoint();
        String clientId = clientIdOf(stored);
        if (refreshToken == null || refreshToken.isBlank() || tokenEndpoint == null) {
            throw new AuthRequiredException("Stored browser login is incomplete — run /login --browser.");
        }
        log.info("access token expired — refreshing at the OAuth token endpoint");
        OAuthClient.Tokens tokens;
        try {
            tokens = oauthClient.refresh(tokenEndpoint, clientId, refreshToken);
        } catch (OAuthClient.OAuthRefusedException e) {
            throw new AuthRequiredException(
                    "Browser login expired or was revoked (" + e.getMessage() + ") — run /login --browser.", e);
        }
        AccessData data = new AccessData();
        data.setUsername(stored.getUsername());
        data.setAccessToken(tokens.accessToken());
        data.setAccessExpiresAt(clock.getAsLong() + tokens.expiresIn() * 1000L);
        data.setRefreshToken(tokens.refreshToken() != null ? tokens.refreshToken() : refreshToken);
        data.setAuthMethod(AUTH_METHOD_OAUTH);
        data.setOauthTokenEndpoint(tokenEndpoint);
        data.setOauthRevocationEndpoint(stored.getOauthRevocationEndpoint());
        data.setOauthClientId(clientId);
        accessStore.save(dir, data);
        return toResponse(data.getAccessToken(), data.getAccessExpiresAt());
    }

    /**
     * Clears the stored access credentials from the active directory. A
     * browser login is also revoked at the OAuth server first (best effort),
     * so the refresh token is dead even if a copy of the file survived;
     * {@link LogoutResult#serverRevokeFailed()} tells the caller when the
     * server did not confirm that. The password login's refresh token is a
     * self-expiring JWT with no server-side revocation, so there logout stays
     * a local delete.
     */
    public LogoutResult logout() {
        Path dir = paths.activeDir();
        if (!accessStore.exists(dir)) {
            // Nothing to do — and no lock file to leave behind in a directory without a login.
            log.info("logout: no credentials in {}", dir);
            return new LogoutResult(false, false);
        }
        try {
            return accessStore.withLock(dir, () -> {
                boolean revokeFailed = false;
                Optional<AccessData> stored = loadQuietly(dir);
                if (stored.isPresent()) {
                    AccessData data = stored.get();
                    if (AUTH_METHOD_OAUTH.equals(data.getAuthMethod())
                            && data.getOauthRevocationEndpoint() != null
                            && data.getRefreshToken() != null) {
                        revokeFailed = !oauthClient.revoke(
                                data.getOauthRevocationEndpoint(), clientIdOf(data), data.getRefreshToken());
                        if (revokeFailed) {
                            log.warn("logout: the OAuth server did not confirm the revocation of the browser login");
                        }
                    }
                }
                boolean removed = accessStore.delete(dir);
                log.info("logout: {} credentials in {}", removed ? "removed" : "no", dir);
                return new LogoutResult(removed, revokeFailed);
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AccessStoreException("Failed to lock " + accessStore.file(dir) + ": " + e.getMessage(), e);
        }
    }

    /** Whether the active directory currently holds a stored credential. */
    public boolean hasStoredCredentials() {
        return accessStore.exists(paths.activeDir());
    }

    private ProjectBinding buildBinding(LoginRequest request) {
        ProjectBinding binding = new ProjectBinding();
        ProjectBinding.Brain brain = new ProjectBinding.Brain();
        brain.setHttpBase(request.httpBase());
        brain.setWsBase(request.wsBase());
        binding.setBrain(brain);
        binding.setTenant(request.tenant());
        binding.setUsername(request.username());
        if (request.project() != null && !request.project().isBlank()) {
            binding.setProject(request.project().trim());
        }
        return binding;
    }

    private void persist(Path dir, String username, AccessTokenResponse minted, AccessData previous) {
        AccessData data = new AccessData();
        data.setUsername(username);
        data.setAccessToken(minted.getToken());
        data.setAccessExpiresAt(minted.getExpiresAtTimestamp());
        // The server rotates the refresh token when requestRefreshToken=true;
        // keep the previous one only if the response omitted a new one.
        data.setRefreshToken(minted.getRefreshToken() != null ? minted.getRefreshToken() : previous.getRefreshToken());
        data.setRefreshExpiresAt(
                minted.getRefreshTokenExpiresAtTimestamp() != null
                        ? minted.getRefreshTokenExpiresAtTimestamp()
                        : previous.getRefreshExpiresAt());
        accessStore.save(dir, data);
    }

    private boolean isValid(@Nullable String token, @Nullable Long expiresAt) {
        if (token == null || token.isBlank() || expiresAt == null) {
            return false;
        }
        return clock.getAsLong() + EXPIRY_SKEW_MS < expiresAt;
    }

    private static AccessTokenResponse toResponse(@Nullable String token, @Nullable Long expiresAt) {
        return AccessTokenResponse.builder()
                .token(token)
                .expiresAtTimestamp(expiresAt == null ? 0L : expiresAt)
                .build();
    }

    private static String firstNonBlank(@Nullable String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
