package de.mhus.vance.shared.tenant;

import de.mhus.vance.shared.keystore.KeyPurpose;
import de.mhus.vance.shared.keystore.KeyService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Tenant lifecycle and lookup — the one entry point to tenant data.
 *
 * <p>Creating a tenant also creates its JWT signing key. A vance tenant without
 * a signing key is unusable (every client needs a JWT to connect), so the two
 * are persisted together. {@link #ensure(String, String)} is idempotent for
 * both: an existing tenant without a key gets one on the next call.
 *
 * <p>On startup the {@value #DEFAULT_TENANT} tenant is ensured so a fresh DB
 * can mint tokens immediately.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantService {

    /** Business name of the default tenant created on first startup. */
    public static final String DEFAULT_TENANT = "default";

    private final TenantRepository repository;
    private final KeyService keyService;

    @PostConstruct
    void bootstrapDefaultTenant() {
        defaultTenant();
    }

    public Optional<TenantDocument> findByName(String name) {
        return repository.findByName(name);
    }

    public List<TenantDocument> all() {
        return repository.findAll();
    }

    /**
     * Creates the tenant (if missing) and its JWT signing key (if missing).
     * Returns the persisted tenant.
     */
    public TenantDocument ensure(String name, @Nullable String title) {
        TenantDocument tenant = repository.findByName(name).orElseGet(() -> {
            TenantDocument created = TenantDocument.builder()
                    .name(name)
                    .title(title)
                    .enabled(true)
                    .build();
            TenantDocument saved = repository.save(created);
            log.info("Created tenant name='{}' id='{}'", saved.getName(), saved.getId());
            return saved;
        });

        ensureJwtKey(tenant);
        return tenant;
    }

    /** Returns the {@value #DEFAULT_TENANT} tenant, creating it on first call. */
    public TenantDocument defaultTenant() {
        return ensure(DEFAULT_TENANT, "Default");
    }

    private void ensureJwtKey(TenantDocument tenant) {
        if (keyService.hasSigningKey(tenant.getName(), KeyPurpose.JWT_SIGNING)) {
            return;
        }
        String keyId = keyService.createAndStoreEcKeyPair(tenant.getName(), KeyPurpose.JWT_SIGNING);
        log.info("Bootstrapped JWT signing key tenant='{}' keyId='{}'", tenant.getName(), keyId);
    }
}
