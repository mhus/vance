package de.mhus.vance.shared.tenant;

import de.mhus.vance.shared.keystore.KeyPurpose;
import de.mhus.vance.shared.keystore.KeyService;
import de.mhus.vance.shared.runner.RunnerBinding;
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
 * <p>On startup the {@value #SYSTEM_TENANT} tenant is ensured so a fresh DB
 * has a usable home for first-party identities ({@code _vance-admin}) and
 * cross-tenant infrastructure right out of the box.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantService {

    /**
     * Business name of the Vance-internal tenant created on first startup.
     * Hosts first-party service accounts (e.g. {@code _vance-admin}) and
     * any future cross-tenant metadata. Customer tenants live alongside
     * it but never inherit from it.
     */
    public static final String SYSTEM_TENANT = "_vance";

    private final TenantRepository repository;
    private final KeyService keyService;

    @PostConstruct
    void bootstrapSystemTenant() {
        systemTenant();
    }

    public Optional<TenantDocument> findByName(String name) {
        return repository.findByName(name);
    }

    public List<TenantDocument> all() {
        return repository.findAll();
    }

    /**
     * Patches mutable fields of an existing tenant. {@code name} is immutable;
     * a {@code null} {@code title}/{@code enabled} means "leave as is".
     *
     * @throws TenantNotFoundException if the tenant does not exist
     */
    public TenantDocument update(String name, @Nullable String title, @Nullable Boolean enabled) {
        TenantDocument tenant = repository
                .findByName(name)
                .orElseThrow(() -> new TenantNotFoundException("Tenant '" + name + "' not found"));
        if (title != null) {
            tenant.setTitle(title);
        }
        if (enabled != null) {
            tenant.setEnabled(enabled);
        }
        TenantDocument saved = repository.save(tenant);
        log.info(
                "Updated tenant name='{}' title='{}' enabled={}", saved.getName(), saved.getTitle(), saved.isEnabled());
        return saved;
    }

    /**
     * Sets or, with {@code null}, removes the tenant's runner binding. Called by
     * {@link de.mhus.vance.shared.runner.RunnerBindingService}, which validates.
     *
     * @throws TenantNotFoundException if the tenant does not exist
     */
    public TenantDocument setRunner(String name, @Nullable RunnerBinding binding) {
        TenantDocument tenant = repository
                .findByName(name)
                .orElseThrow(() -> new TenantNotFoundException("Tenant '" + name + "' not found"));
        tenant.setRunner(binding);
        return repository.save(tenant);
    }

    /**
     * Creates the tenant (if missing) and its JWT signing key (if missing).
     * Returns the persisted tenant.
     */
    public TenantDocument ensure(String name, @Nullable String title) {
        TenantDocument tenant = repository.findByName(name).orElseGet(() -> create(name, title));

        ensureJwtKey(tenant);
        return tenant;
    }

    /**
     * Inserts the tenant, tolerating a lost race against another pod that
     * booted against the same fresh database and inserted the same row
     * between our read and this write.
     *
     * <p>Two pods starting simultaneously is the normal case in Kubernetes,
     * and this method is on the unconditional boot path — it runs from
     * {@link #bootstrapSystemTenant()} in a {@code @PostConstruct}, so an
     * exception here does not fail a request, it fails the process. The
     * unique index on {@code name} is what turns the collision into an
     * exception rather than a duplicate row, which also makes the recovery
     * exact: if the row is there now, it is the other pod's and "ensure" is
     * satisfied. Anything else is a real failure and keeps its original
     * exception.
     */
    private TenantDocument create(String name, @Nullable String title) {
        TenantDocument created =
                TenantDocument.builder().name(name).title(title).enabled(true).build();
        try {
            TenantDocument saved = repository.save(created);
            log.info("Created tenant name='{}' id='{}'", saved.getName(), saved.getId());
            return saved;
        } catch (RuntimeException e) {
            TenantDocument concurrent = repository.findByName(name).orElseThrow(() -> e);
            log.info("Tenant name='{}' was created concurrently by another pod", name);
            return concurrent;
        }
    }

    /** Returns the {@value #SYSTEM_TENANT} tenant, creating it on first call. */
    public TenantDocument systemTenant() {
        return ensure(SYSTEM_TENANT, "Vance internal");
    }

    /**
     * Removes the tenant's own row — and nothing else.
     *
     * <p>A tenant's data lives in dozens of collections; this method touches none of them. It is the last
     * step of {@code TenantMaintenanceService} in the admin shell, which removes the projects, the accounts and
     * everything else keyed by the tenant first and only then lets the row go: the row is the index back to
     * that data, and dropping it while data is left would strand the data with no way to address it. Do not call
     * this from anywhere else.
     *
     * <p>Two invariants live here because they belong to the entity, not to a caller's care: the system tenant
     * and any name starting with {@code _} are never removed, and a tenant that is still enabled is never
     * removed — switching it off is the deliberate step before, and what makes sure nobody is logging in
     * while the data goes.
     *
     * @throws TenantNotFoundException if the tenant does not exist
     * @throws TenantProtectedException for the system tenant and {@code _}-prefixed names
     * @throws TenantStillEnabledException if the tenant is enabled
     */
    public void delete(String name) {
        if (SYSTEM_TENANT.equals(name) || name.startsWith("_")) {
            throw new TenantProtectedException("Tenant '" + name + "' is a system tenant and is never removed");
        }
        TenantDocument tenant = repository
                .findByName(name)
                .orElseThrow(() -> new TenantNotFoundException("Tenant '" + name + "' not found"));
        if (tenant.isEnabled()) {
            throw new TenantStillEnabledException("Tenant '" + name + "' is still enabled — disable it first");
        }
        repository.delete(tenant);
        log.warn("Removed tenant name='{}' id='{}'", name, tenant.getId());
    }

    /**
     * Creates the tenant's JWT signing key if it has none.
     *
     * <p>Unlike {@link #create(String, String)} this needs no collision
     * handling: two pods racing here end up with two key pairs, and
     * {@code JwtService.validateToken} verifies against <em>every</em>
     * enabled public key of the tenant, so tokens minted by either pod are
     * accepted by both. The duplicate is untidy, not harmful — and there is
     * no unique index to lean on, so guarding it would mean a lease.
     */
    private void ensureJwtKey(TenantDocument tenant) {
        if (keyService.hasSigningKey(tenant.getName(), KeyPurpose.JWT_SIGNING)) {
            return;
        }
        String keyId = keyService.createAndStoreEcKeyPair(tenant.getName(), KeyPurpose.JWT_SIGNING);
        log.info("Bootstrapped JWT signing key tenant='{}' keyId='{}'", tenant.getName(), keyId);
    }

    public static class TenantNotFoundException extends RuntimeException {
        public TenantNotFoundException(String message) {
            super(message);
        }
    }

    /** The system tenant, or a {@code _}-prefixed name: never removed. */
    public static class TenantProtectedException extends RuntimeException {
        public TenantProtectedException(String message) {
            super(message);
        }
    }

    /** A tenant must be switched off before it can be removed. */
    public static class TenantStillEnabledException extends RuntimeException {
        public TenantStillEnabledException(String message) {
            super(message);
        }
    }
}
