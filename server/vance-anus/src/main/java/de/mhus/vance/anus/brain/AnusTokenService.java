package de.mhus.vance.anus.brain;

import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Mints short-lived admin JWTs for the Anus shell.
 *
 * <p>Identity model: per target tenant Anus uses a dedicated
 * {@code _vance-admin} service account (created lazily, lives forever
 * once minted). The account's password hash is a random UUID — Anus
 * never logs in with it and the field exists only so a manual "flip the
 * loginEnabled flag" attempt can't accidentally produce a usable
 * credential. Tokens are signed with the tenant's normal JWT signing
 * key, so the Brain validates them through its standard filter without
 * any special case.
 *
 * <p>Tokens are not cached. Every call re-mints — they are cheap, and
 * caching would add lifecycle questions (when does the token expire,
 * who refreshes, what happens after a key rotation) for no real win at
 * the call rate of an interactive admin shell.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnusTokenService {

    /** Username of the per-tenant Anus admin identity. */
    public static final String ADMIN_USERNAME = "_vance-admin";

    private final TenantService tenantService;
    private final UserService userService;
    private final JwtService jwtService;
    private final AnusBrainProperties properties;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    /**
     * Returns a freshly-signed admin token for {@code tenantName}.
     * Ensures the target tenant exists (with its JWT signing key) and
     * the per-tenant {@code _vance-admin} service account is in place
     * before signing.
     *
     * @throws IllegalStateException if the tenant has no signing key
     *                               (should never happen — TenantService
     *                               mints one on ensure)
     */
    public String mintAdminToken(String tenantName) {
        // ensure(tenant) is idempotent and also bootstraps the JWT key
        // for fresh tenants — Anus may legitimately be the first thing
        // touching a brand-new tenant after a manual mongo seed.
        tenantService.ensure(tenantName, null);
        ensureAdminUser(tenantName);

        Instant expiresAt = Instant.now().plus(properties.getAdminTokenTtl());
        String token = jwtService.createToken(tenantName, ADMIN_USERNAME, expiresAt, TokenType.ACCESS);
        log.debug("Minted Anus admin token tenant='{}' ttl={}",
                tenantName, properties.getAdminTokenTtl());
        return token;
    }

    /**
     * Idempotent ensure of {@code _vance-admin} inside {@code tenantName}.
     * On first call mints a random UUID password hash so the field is
     * not blank. The user always has {@code serviceAccount=true,
     * loginEnabled=false} — the password is unreachable by design.
     */
    public UserDocument ensureAdminUser(String tenantName) {
        String randomHash = encoder.encode(UUID.randomUUID().toString());
        return userService.ensureVanceServiceAccount(
                tenantName,
                ADMIN_USERNAME,
                randomHash,
                properties.getAdminUserTitle(),
                /* email */ null);
    }
}
