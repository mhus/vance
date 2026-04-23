package de.mhus.vance.brain.init;

import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.shared.user.UserService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Brain startup hook: makes sure the {@value #ACME_TENANT} tenant exists and
 * is populated with the demo/test seed users.
 *
 * <p>Seed credentials (plaintext, stored hashed via BCrypt):
 * <ul>
 *   <li>{@code wile.coyote} / {@code acme-rocket}</li>
 *   <li>{@code road.runner} / {@code beep-beep}</li>
 * </ul>
 *
 * <p>Everything is idempotent — {@link TenantService#ensure(String, String)}
 * and the local {@link #ensureUser} helper short-circuit on existing records.
 * Runs after {@link TenantService}'s own {@code @PostConstruct} (which creates
 * the {@code default} tenant), so on a fresh database both tenants and their
 * users are present by the time the application finishes starting up.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InitBrainService {

    public static final String ACME_TENANT = "acme";

    private final TenantService tenantService;
    private final UserService userService;
    private final PasswordService passwordService;

    @PostConstruct
    void init() {
        tenantService.ensure(ACME_TENANT, "Acme");

        ensureUser(ACME_TENANT, "wile.coyote", "Wile E. Coyote", "wile.e.coyote@acme.com", "acme-rocket");
        ensureUser(ACME_TENANT, "road.runner", "Road Runner", "beep.beep@acme.com", "beep-beep");
    }

    private void ensureUser(String tenantId, String name, String title, @Nullable String email, String plainPassword) {
        if (userService.existsByTenantAndName(tenantId, name)) {
            return;
        }
        String hash = passwordService.hash(plainPassword);
        userService.create(tenantId, name, hash, title, email);
    }
}
