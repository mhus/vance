package de.mhus.vance.foot.auth;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.foot.config.FootConfig;
import java.nio.file.Path;
import java.util.Optional;
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
 * {@code access.yaml} (secret) and {@code project.yaml} (binding) to the
 * login target directory, and applies the binding to the running config.
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
    private final LongSupplier clock;

    @Autowired
    public FootAuthService(FootConfig config,
                           VancePaths paths,
                           AccessStore accessStore,
                           ProjectBindingStore bindingStore,
                           ProjectBindingApplier bindingApplier,
                           AccessTokenClient client) {
        this(config, paths, accessStore, bindingStore, bindingApplier, client,
                System::currentTimeMillis);
    }

    /** Test constructor with an injectable clock. */
    FootAuthService(FootConfig config,
                    VancePaths paths,
                    AccessStore accessStore,
                    ProjectBindingStore bindingStore,
                    ProjectBindingApplier bindingApplier,
                    AccessTokenClient client,
                    LongSupplier clock) {
        this.config = config;
        this.paths = paths;
        this.accessStore = accessStore;
        this.bindingStore = bindingStore;
        this.bindingApplier = bindingApplier;
        this.client = client;
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
            if (isValid(stored.getRefreshToken(), stored.getRefreshExpiresAt())) {
                String username = firstNonBlank(stored.getUsername(), config.getAuth().getUsername());
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
     * Performs an interactive password login: mints with a refresh token,
     * writes {@code access.yaml} + {@code project.yaml} to the login target
     * directory, and overlays the binding onto the running config.
     */
    public LoginResult login(LoginRequest request) throws Exception {
        TransportGuard.assertAllowed(
                request.httpBase(), request.wsBase(),
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
        accessStore.save(dir, data);

        ProjectBinding binding = buildBinding(request);
        bindingStore.save(dir, binding);
        bindingApplier.apply(binding, config);

        log.info("login succeeded: user='{}' tenant='{}' project='{}' dir={}",
                request.username(), request.tenant(), request.project(), dir);
        return new LoginResult(dir, minted, binding);
    }

    /**
     * Clears the stored access credentials from the active directory.
     * Returns {@code true} if a credential file was removed. (The refresh
     * token is a self-expiring JWT with no server-side revocation yet, so
     * logout is a local delete — see {@code specification/cli-token-auth-plan.md}.)
     */
    public boolean logout() {
        Path dir = paths.activeDir();
        boolean removed = accessStore.delete(dir);
        log.info("logout: {} credentials in {}", removed ? "removed" : "no", dir);
        return removed;
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
        data.setRefreshToken(minted.getRefreshToken() != null
                ? minted.getRefreshToken() : previous.getRefreshToken());
        data.setRefreshExpiresAt(minted.getRefreshTokenExpiresAtTimestamp() != null
                ? minted.getRefreshTokenExpiresAtTimestamp() : previous.getRefreshExpiresAt());
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
