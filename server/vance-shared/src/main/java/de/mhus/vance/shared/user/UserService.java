package de.mhus.vance.shared.user;

import de.mhus.vance.shared.session.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * User lifecycle and lookup — the one entry point to user data.
 *
 * <p>Password hashing is up to the caller — this service stores whatever hash
 * it is given and never sees plaintext.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    /**
     * Reserved name prefix that identifies a service account (no
     * password login). Anything beginning with this is rejected by
     * {@link #create} and routed through {@link #createServiceAccount}
     * instead, which sets the {@code serviceAccount} / {@code loginEnabled}
     * fields appropriately.
     */
    public static final String SERVICE_ACCOUNT_PREFIX = "_";

    /**
     * Reserved sub-prefix for Vance-internal identities (e.g.
     * {@code _vance-admin}). Even {@link #createServiceAccount} refuses
     * names starting with this — only {@link #ensureVanceServiceAccount}
     * (callable from bootstrap and the Anus shell) is allowed to mint
     * them. Keeps the namespace authoritative for first-party use.
     */
    public static final String RESERVED_VANCE_PREFIX = "_vance-";

    /**
     * Names that can never belong to a principal. {@link SessionService#SYSTEM_OWNER}
     * is the placeholder owner of server-owned system sessions, and the
     * think-engine path reads it as "no user" — which becomes
     * {@code SecurityContext.SYSTEM}. A real account carrying that name would
     * therefore inherit the system trust boundary, so no creation path may
     * mint it.
     */
    private static final List<String> RESERVED_NAMES = List.of(SessionService.SYSTEM_OWNER);

    /**
     * Consecutive failed password logins that trigger a temporary lockout.
     */
    public static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;

    /**
     * How long an account stays locked after {@link #MAX_FAILED_LOGIN_ATTEMPTS}
     * failures. The lock auto-expires — no admin action required.
     */
    public static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private static final String F_TENANT_ID = "tenantId";
    private static final String F_NAME = "name";
    private static final String F_FAILED_ATTEMPTS = "failedLoginAttempts";
    private static final String F_LOCKED_UNTIL = "lockedUntil";
    private static final String F_LAST_FAILED = "lastFailedLoginAt";

    private final UserRepository repository;
    private final MongoTemplate mongoTemplate;

    public Optional<UserDocument> findByTenantAndName(String tenantId, String name) {
        return repository.findByTenantIdAndName(tenantId, name);
    }

    public boolean existsByTenantAndName(String tenantId, String name) {
        return repository.existsByTenantIdAndName(tenantId, name);
    }

    public List<UserDocument> all(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    /**
     * Creates a regular human user inside {@code tenantId} with
     * {@link UserStatus#ACTIVE} and {@code loginEnabled=true}. Names
     * starting with {@link #SERVICE_ACCOUNT_PREFIX} are rejected — those
     * must be created via {@link #createServiceAccount}.
     *
     * @throws UserAlreadyExistsException if a user with the same {@code name}
     *                                    already lives in that tenant
     * @throws ReservedNameException      if {@code name} starts with the
     *                                    reserved service-account prefix
     */
    public UserDocument create(
            String tenantId,
            String name,
            @Nullable String passwordHash,
            @Nullable String title,
            @Nullable String email) {
        if (name.startsWith(SERVICE_ACCOUNT_PREFIX)) {
            throw new ReservedNameException(
                    "User name '" + name + "' starts with the reserved '"
                            + SERVICE_ACCOUNT_PREFIX + "' prefix — service accounts "
                            + "must be created via createServiceAccount(...)");
        }
        return doCreate(tenantId, name, passwordHash, title, email,
                /* loginEnabled */ true,
                /* serviceAccount */ false);
    }

    /**
     * Creates a service account (e.g. for an integration or daemon).
     * Names must start with {@link #SERVICE_ACCOUNT_PREFIX} but NOT with
     * {@link #RESERVED_VANCE_PREFIX} — the latter is reserved for
     * Vance-internal identities. The created user has {@code loginEnabled=false}
     * and {@code serviceAccount=true}; tokens for it can only be minted
     * out-of-band (e.g. by the Anus admin shell).
     *
     * @throws ReservedNameException      if the name violates the prefix rules
     * @throws UserAlreadyExistsException on duplicate
     */
    public UserDocument createServiceAccount(
            String tenantId,
            String name,
            @Nullable String passwordHash,
            @Nullable String title,
            @Nullable String email) {
        if (!name.startsWith(SERVICE_ACCOUNT_PREFIX)) {
            throw new ReservedNameException(
                    "Service account name '" + name + "' must start with '"
                            + SERVICE_ACCOUNT_PREFIX + "'");
        }
        if (name.startsWith(RESERVED_VANCE_PREFIX)) {
            throw new ReservedNameException(
                    "Service account name '" + name + "' is in the reserved "
                            + "Vance-internal namespace ('" + RESERVED_VANCE_PREFIX
                            + "*') — use ensureVanceServiceAccount(...) instead");
        }
        return doCreate(tenantId, name, passwordHash, title, email,
                /* loginEnabled */ false,
                /* serviceAccount */ true);
    }

    /**
     * Idempotently ensures a Vance-internal service account exists in
     * {@code tenantId}. Names must start with {@link #RESERVED_VANCE_PREFIX}
     * (e.g. {@code _vance-admin}). On first call a fresh user is minted
     * with {@code loginEnabled=false} and {@code serviceAccount=true};
     * subsequent calls return the existing record unchanged. Only the
     * bootstrap path and the Anus admin shell should call this — REST
     * endpoints must not.
     *
     * @throws ReservedNameException if the name does not start with
     *                               {@link #RESERVED_VANCE_PREFIX}
     */
    public UserDocument ensureVanceServiceAccount(
            String tenantId,
            String name,
            @Nullable String passwordHash,
            @Nullable String title,
            @Nullable String email) {
        if (!name.startsWith(RESERVED_VANCE_PREFIX)) {
            throw new ReservedNameException(
                    "Vance-internal service account name '" + name
                            + "' must start with '" + RESERVED_VANCE_PREFIX + "'");
        }
        return repository.findByTenantIdAndName(tenantId, name)
                .orElseGet(() -> doCreate(tenantId, name, passwordHash, title, email,
                        /* loginEnabled */ false,
                        /* serviceAccount */ true));
    }

    private UserDocument doCreate(
            String tenantId,
            String name,
            @Nullable String passwordHash,
            @Nullable String title,
            @Nullable String email,
            boolean loginEnabled,
            boolean serviceAccount) {
        if (RESERVED_NAMES.contains(name)) {
            throw new ReservedNameException(
                    "User name '" + name + "' is reserved for internal, "
                            + "non-principal use and cannot be created");
        }
        if (repository.existsByTenantIdAndName(tenantId, name)) {
            throw new UserAlreadyExistsException(
                    "User '" + name + "' already exists in tenant '" + tenantId + "'");
        }
        UserDocument user = UserDocument.builder()
                .tenantId(tenantId)
                .name(name)
                .passwordHash(passwordHash)
                .title(title)
                .email(email)
                .status(UserStatus.ACTIVE)
                .loginEnabled(loginEnabled)
                .serviceAccount(serviceAccount)
                .build();
        UserDocument saved = repository.save(user);
        log.info("Created user tenantId='{}' name='{}' id='{}' serviceAccount={} loginEnabled={}",
                saved.getTenantId(), saved.getName(), saved.getId(),
                saved.isServiceAccount(), saved.isLoginEnabled());
        return saved;
    }

    /**
     * Patches mutable fields. {@code name} and {@code tenantId} are
     * immutable; password is set separately via {@link #setPasswordHash}.
     * {@code null} fields mean "leave as is".
     *
     * <p>{@code loginEnabled} and {@code serviceAccount} are
     * <strong>orthogonal</strong>: a service account may have login
     * enabled (foot-daemon-style — bot, but still uses password login)
     * or disabled (Anus-managed token-only bot). Default for service
     * accounts created via {@link #createServiceAccount} is
     * {@code loginEnabled=false}; an admin flips it through this
     * method when the account needs to drive a daemon process with
     * the standard login endpoint.
     *
     * @throws UserNotFoundException if the user does not exist
     */
    public UserDocument update(
            String tenantId,
            String name,
            @Nullable String title,
            @Nullable String email,
            @Nullable UserStatus status,
            @Nullable Boolean loginEnabled) {
        UserDocument user = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new UserNotFoundException(
                        "User '" + name + "' not found in tenant '" + tenantId + "'"));
        if (title != null) {
            user.setTitle(title);
        }
        if (email != null) {
            user.setEmail(email);
        }
        if (status != null) {
            user.setStatus(status);
        }
        if (loginEnabled != null) {
            user.setLoginEnabled(loginEnabled);
        }
        UserDocument saved = repository.save(user);
        log.info("Updated user tenantId='{}' name='{}' status={} loginEnabled={} serviceAccount={}",
                saved.getTenantId(), saved.getName(), saved.getStatus(),
                saved.isLoginEnabled(), saved.isServiceAccount());
        return saved;
    }

    /**
     * Convenience helper: flip the {@code loginEnabled} flag on an
     * existing account without touching anything else. Same orthogonality
     * rule as {@link #update} — service accounts may have login enabled.
     *
     * @throws UserNotFoundException if the user does not exist
     */
    public UserDocument setLoginEnabled(String tenantId, String name, boolean loginEnabled) {
        return update(tenantId, name, null, null, null, loginEnabled);
    }

    /**
     * Replaces the user's password hash. Caller hashes; service stores.
     * Also stamps {@code passwordChangedAt} and clears any lockout state —
     * a fresh password starts the login-failure window over.
     */
    public UserDocument setPasswordHash(String tenantId, String name, String passwordHash) {
        UserDocument user = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new UserNotFoundException(
                        "User '" + name + "' not found in tenant '" + tenantId + "'"));
        user.setPasswordHash(passwordHash);
        user.setPasswordChangedAt(Instant.now());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastFailedLoginAt(null);
        UserDocument saved = repository.save(user);
        log.info("Reset password tenantId='{}' name='{}'", saved.getTenantId(), saved.getName());
        return saved;
    }

    // ──────────────────── Brute-force lockout ────────────────────

    /**
     * Whether {@code user} is currently locked out — i.e. it has a
     * {@link UserDocument#getLockedUntil() lockedUntil} in the future. Pure
     * check against the given document, no DB access.
     */
    public boolean isLocked(UserDocument user) {
        Instant until = user.getLockedUntil();
        return until != null && Instant.now().isBefore(until);
    }

    /**
     * Atomically records one failed password login. Increments the failure
     * counter and stamps {@code lastFailedLoginAt}; once the counter reaches
     * {@link #MAX_FAILED_LOGIN_ATTEMPTS} the account is locked for
     * {@link #LOCKOUT_DURATION} (and the counter reset, so the window starts
     * fresh after the lock expires). No-op if the user does not exist.
     */
    public void recordFailedLogin(String tenantId, String name) {
        Instant now = Instant.now();
        Query query = new Query(Criteria.where(F_TENANT_ID).is(tenantId).and(F_NAME).is(name));
        Update update = new Update().inc(F_FAILED_ATTEMPTS, 1).set(F_LAST_FAILED, now);
        UserDocument updated = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                UserDocument.class);
        if (updated == null) {
            return;
        }
        if (updated.getFailedLoginAttempts() >= MAX_FAILED_LOGIN_ATTEMPTS) {
            Instant lockedUntil = now.plus(LOCKOUT_DURATION);
            Update lock = new Update().set(F_LOCKED_UNTIL, lockedUntil).set(F_FAILED_ATTEMPTS, 0);
            mongoTemplate.updateFirst(query, lock, UserDocument.class);
            log.warn("Account locked after {} failed logins tenantId='{}' name='{}' until={}",
                    MAX_FAILED_LOGIN_ATTEMPTS, tenantId, name, lockedUntil);
        }
    }

    /**
     * Atomically clears the failure counter and any lockout for a user —
     * called after a successful login. Best-effort; a no-op if the user does
     * not exist.
     */
    public void resetLoginFailures(String tenantId, String name) {
        Query query = new Query(Criteria.where(F_TENANT_ID).is(tenantId).and(F_NAME).is(name));
        Update update = new Update()
                .set(F_FAILED_ATTEMPTS, 0)
                .set(F_LOCKED_UNTIL, null)
                .set(F_LAST_FAILED, null);
        mongoTemplate.updateFirst(query, update, UserDocument.class);
    }

    /**
     * Hard-deletes a user. Memberships in teams are left untouched —
     * callers concerned about referential integrity should clean those
     * up via {@code TeamService.removeMember} before / after.
     */
    public void delete(String tenantId, String name) {
        UserDocument user = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new UserNotFoundException(
                        "User '" + name + "' not found in tenant '" + tenantId + "'"));
        repository.delete(user);
        log.info("Deleted user tenantId='{}' name='{}'", tenantId, name);
    }

    /** Thrown by {@link #create(String, String, String, String, String)} on a duplicate. */
    public static class UserAlreadyExistsException extends RuntimeException {
        public UserAlreadyExistsException(String message) {
            super(message);
        }
    }

    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when a name violates the reserved-prefix rules — either a
     * regular {@link #create} sees a {@code _}-prefixed name, a
     * {@link #createServiceAccount} sees a {@code _vance-} name, or
     * {@link #ensureVanceServiceAccount} is called with a non-reserved name.
     */
    public static class ReservedNameException extends RuntimeException {
        public ReservedNameException(String message) {
            super(message);
        }
    }

}
