package de.mhus.vance.shared.user;

import de.mhus.vance.shared.session.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
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
    private static final String F_EXT_ISSUER = "externalIdentity.issuer";
    private static final String F_EXT_SUBJECT = "externalIdentity.subject";
    private static final String F_EXT_EXPECTED_EMAIL = "externalIdentity.expectedEmail";
    private static final String F_EXT_EMAIL = "externalIdentity.email";
    private static final String F_EXT_LINKED_AT = "externalIdentity.linkedAt";
    private static final String F_EXT_LAST_LOGIN_AT = "externalIdentity.lastLoginAt";

    private final UserRepository repository;
    private final MongoTemplate mongoTemplate;
    /**
     * Everything that keys on the user <em>name</em> rather than on the Mongo
     * id — grant storage above all. Empty unless an addon contributes one; see
     * {@link UserLifecycleListener}.
     */
    private final ObjectProvider<UserLifecycleListener> lifecycleListeners;

    private final de.mhus.vance.shared.megadodo.MegadodoService megadodoService;
    /** Carries {@link UserPasswordChangedEvent} to whoever ends credentials on a password change. */
    private final ApplicationEventPublisher events;

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
            throw new ReservedNameException("User name '" + name + "' starts with the reserved '"
                    + SERVICE_ACCOUNT_PREFIX + "' prefix — service accounts "
                    + "must be created via createServiceAccount(...)");
        }
        return doCreate(
                tenantId, name, passwordHash, title, email, /* loginEnabled */ true, /* serviceAccount */ false);
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
                    "Service account name '" + name + "' must start with '" + SERVICE_ACCOUNT_PREFIX + "'");
        }
        if (name.startsWith(RESERVED_VANCE_PREFIX)) {
            throw new ReservedNameException("Service account name '" + name + "' is in the reserved "
                    + "Vance-internal namespace ('" + RESERVED_VANCE_PREFIX
                    + "*') — use ensureVanceServiceAccount(...) instead");
        }
        return doCreate(
                tenantId, name, passwordHash, title, email, /* loginEnabled */ false, /* serviceAccount */ true);
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
            throw new ReservedNameException("Vance-internal service account name '" + name + "' must start with '"
                    + RESERVED_VANCE_PREFIX + "'");
        }
        Optional<UserDocument> existing = repository.findByTenantIdAndName(tenantId, name);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return doCreate(
                    tenantId, name, passwordHash, title, email, /* loginEnabled */ false, /* serviceAccount */ true);
        } catch (RuntimeException e) {
            // Another pod booting against the same fresh database created the
            // account between our read and the write. Two windows lead here:
            // doCreate's own existence check (which throws
            // UserAlreadyExistsException) and the unique index on
            // (tenantId, name) behind it. Either way an account that is there
            // now satisfies "ensure" — anything else keeps its exception.
            UserDocument concurrent =
                    repository.findByTenantIdAndName(tenantId, name).orElseThrow(() -> e);
            log.info(
                    "Service account '{}' in tenant '{}' was created concurrently " + "by another pod", name, tenantId);
            return concurrent;
        }
    }

    private UserDocument doCreate(
            String tenantId,
            String name,
            @Nullable String passwordHash,
            @Nullable String title,
            @Nullable String email,
            boolean loginEnabled,
            boolean serviceAccount) {
        return doCreate(tenantId, name, passwordHash, title, email, loginEnabled, serviceAccount, null);
    }

    /**
     * Creates the account in one write. An {@code externalIdentity} goes into
     * that same first write: saved without it and patched afterwards, a
     * collision on the identity indexes would leave a password-less account
     * behind that nobody asked for — and the lifecycle listeners would already
     * have seen it.
     *
     * @throws ExternalIdentityException when {@code externalIdentity} collides
     *     with another account's (unique index on subject or prepared address)
     */
    private UserDocument doCreate(
            String tenantId,
            String name,
            @Nullable String passwordHash,
            @Nullable String title,
            @Nullable String email,
            boolean loginEnabled,
            boolean serviceAccount,
            @Nullable ExternalIdentity externalIdentity) {
        if (RESERVED_NAMES.contains(name)) {
            throw new ReservedNameException(
                    "User name '" + name + "' is reserved for internal, " + "non-principal use and cannot be created");
        }
        if (repository.existsByTenantIdAndName(tenantId, name)) {
            throw new UserAlreadyExistsException("User '" + name + "' already exists in tenant '" + tenantId + "'");
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
                .externalIdentity(externalIdentity)
                .build();
        UserDocument saved;
        try {
            saved = repository.save(user);
        } catch (DuplicateKeyException e) {
            if (externalIdentity == null) {
                throw e;
            }
            throw identityConflict(tenantId, name, e);
        }
        megadodoService.userCreated(tenantId, name, serviceAccount);
        // Best-effort, and one listener's failure does not hide the next:
        // the account exists by now, so there is nothing left to abort.
        lifecycleListeners.orderedStream().forEach(listener -> {
            try {
                listener.onUserCreated(saved);
            } catch (RuntimeException e) {
                log.warn(
                        "User-lifecycle listener {} failed for created user '{}' in tenant '{}'",
                        listener.getClass().getSimpleName(),
                        name,
                        tenantId,
                        e);
            }
        });
        log.info(
                "Created user tenantId='{}' name='{}' id='{}' serviceAccount={} loginEnabled={}",
                saved.getTenantId(),
                saved.getName(),
                saved.getId(),
                saved.isServiceAccount(),
                saved.isLoginEnabled());
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
        UserDocument user = repository
                .findByTenantIdAndName(tenantId, name)
                .orElseThrow(
                        () -> new UserNotFoundException("User '" + name + "' not found in tenant '" + tenantId + "'"));
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
        log.info(
                "Updated user tenantId='{}' name='{}' status={} loginEnabled={} serviceAccount={}",
                saved.getTenantId(),
                saved.getName(),
                saved.getStatus(),
                saved.isLoginEnabled(),
                saved.isServiceAccount());
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
     *
     * <p>Publishes {@link UserPasswordChangedEvent} once the hash is stored, so
     * long-lived credentials issued under the old password (OAuth grants) end
     * with it.
     */
    public UserDocument setPasswordHash(String tenantId, String name, String passwordHash) {
        UserDocument user = repository
                .findByTenantIdAndName(tenantId, name)
                .orElseThrow(
                        () -> new UserNotFoundException("User '" + name + "' not found in tenant '" + tenantId + "'"));
        if (user.getExternalIdentity() != null) {
            // An SSO account has exactly one way in. A password next to it would
            // survive offboarding at the identity provider.
            throw new ExternalIdentityException("User '" + name + "' in tenant '" + tenantId
                    + "' signs in via " + user.getExternalIdentity().getProvider()
                    + " and cannot have a password — unlink the external identity first");
        }
        user.setPasswordHash(passwordHash);
        user.setPasswordChangedAt(Instant.now());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastFailedLoginAt(null);
        UserDocument saved = repository.save(user);
        log.info("Reset password tenantId='{}' name='{}'", saved.getTenantId(), saved.getName());
        events.publishEvent(new UserPasswordChangedEvent(saved.getTenantId(), saved.getName()));
        return saved;
    }

    // ──────────────────── External sign-in (SSO) ────────────────────

    /**
     * The account bound to {@code (issuer, subject)}, in any tenant. The
     * issuer is compared in its canonical spelling
     * ({@link ExternalIdentity#canonicalIssuer}).
     */
    public Optional<UserDocument> findByExternalSubject(String issuer, String subject) {
        Query query = new Query(Criteria.where(F_EXT_ISSUER)
                .is(ExternalIdentity.canonicalIssuer(issuer))
                .and(F_EXT_SUBJECT)
                .is(subject));
        return Optional.ofNullable(mongoTemplate.findOne(query, UserDocument.class));
    }

    /**
     * The account in {@code tenantId} prepared — not yet linked — for
     * {@code normalizedEmail} (see {@link ExternalEmails#normalize}).
     */
    public Optional<UserDocument> findPreparedByEmail(String tenantId, String normalizedEmail) {
        Query query = new Query(Criteria.where(F_TENANT_ID)
                .is(tenantId)
                .and(F_EXT_EXPECTED_EMAIL)
                .is(normalizedEmail)
                .and(F_EXT_SUBJECT)
                .isNull());
        return Optional.ofNullable(mongoTemplate.findOne(query, UserDocument.class));
    }

    /**
     * The account in {@code tenantId} that holds {@code normalizedEmail} as its
     * prepared address — still waiting or already linked. The address stays on
     * the account after the first sign-in, and the unique index counts both.
     */
    private Optional<UserDocument> findByExpectedEmail(String tenantId, String normalizedEmail) {
        Query query = new Query(Criteria.where(F_TENANT_ID)
                .is(tenantId)
                .and(F_EXT_EXPECTED_EMAIL)
                .is(normalizedEmail));
        return Optional.ofNullable(mongoTemplate.findOne(query, UserDocument.class));
    }

    /**
     * Prepares an account for an external sign-in: the first sign-in from
     * {@code email} at {@code issuer} will be bound to it.
     *
     * <p>A missing account is created without a password — with the identity
     * in the same write. An existing one loses its password; every login token
     * issued before ({@link UserDocument#getTokensNotBefore()}) is void, and
     * {@link UserPasswordChangedEvent} ends the OAuth grants — afterwards the
     * external sign-in is the only way in. Service accounts are refused, as are
     * accounts already bound to an external subject (unlink first).
     *
     * @throws ExternalIdentityException if the address is malformed, uses plus
     *     addressing, is already held by another account, or the account cannot
     *     be prepared
     * @throws ReservedNameException on a service-account name for a new account
     */
    public UserDocument prepareExternalIdentity(
            String tenantId, String name, String provider, String issuer, String email) {
        String normalized = ExternalEmails.normalize(email);
        if (normalized == null || ExternalEmails.hasPlusTag(normalized)) {
            throw new ExternalIdentityException("'" + email + "' is not a usable address for an external sign-in");
        }
        Optional<UserDocument> holder = findByExpectedEmail(tenantId, normalized);
        if (holder.isPresent() && !holder.get().getName().equals(name)) {
            throw new ExternalIdentityException("'" + normalized + "' is already "
                    + (isLinked(holder.get()) ? "linked to" : "prepared for")
                    + " user '" + holder.get().getName() + "' in tenant '" + tenantId + "'");
        }
        Instant now = Instant.now();
        ExternalIdentity identity = ExternalIdentity.builder()
                .provider(provider)
                .issuer(ExternalIdentity.canonicalIssuer(issuer))
                .expectedEmail(normalized)
                .preparedAt(now)
                .build();
        Optional<UserDocument> existing = repository.findByTenantIdAndName(tenantId, name);
        if (existing.isEmpty()) {
            if (name.startsWith(SERVICE_ACCOUNT_PREFIX)) {
                throw new ReservedNameException("User name '" + name + "' starts with the reserved '"
                        + SERVICE_ACCOUNT_PREFIX + "' prefix — service accounts cannot sign in externally");
            }
            UserDocument saved = doCreate(
                    tenantId,
                    name,
                    null,
                    null,
                    normalized,
                    /* loginEnabled */ true,
                    /* serviceAccount */ false,
                    identity);
            log.info(
                    "Prepared new user for external sign-in tenantId='{}' name='{}' provider='{}'",
                    tenantId,
                    name,
                    provider);
            return saved;
        }
        UserDocument user = existing.get();
        if (user.isServiceAccount() || name.startsWith(SERVICE_ACCOUNT_PREFIX)) {
            throw new ExternalIdentityException(
                    "User '" + name + "' is a service account and cannot sign in externally");
        }
        if (isLinked(user)) {
            throw new ExternalIdentityException(
                    "User '" + name + "' is already linked to an external identity" + " — unlink it first");
        }
        user.setPasswordHash(null);
        user.setPasswordChangedAt(now);
        // Sessions started with the password end here — the access filter and
        // every re-mint refuse tokens issued up to now.
        user.setTokensNotBefore(now);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastFailedLoginAt(null);
        user.setExternalIdentity(identity);
        if (user.getEmail() == null) {
            user.setEmail(normalized);
        }
        UserDocument saved;
        try {
            saved = repository.save(user);
        } catch (DuplicateKeyException e) {
            // A concurrent preparation took the address between check and write.
            throw identityConflict(tenantId, name, e);
        }
        log.info(
                "Prepared user for external sign-in tenantId='{}' name='{}' provider='{}' (password removed)",
                tenantId,
                name,
                provider);
        events.publishEvent(new UserPasswordChangedEvent(tenantId, name));
        return saved;
    }

    /**
     * Binds a prepared account to {@code subject} — atomically, so two
     * concurrent first sign-ins cannot both win. Matches only an account still
     * unbound and prepared for exactly {@code normalizedEmail} at
     * {@code issuer}.
     *
     * @return the linked account, or empty when no such prepared account is
     *     (still) there, or the subject is already bound to another account
     */
    public Optional<UserDocument> linkExternalIdentity(
            String tenantId, String name, String issuer, String normalizedEmail, String subject, String email) {
        Instant now = Instant.now();
        String canonicalIssuer = ExternalIdentity.canonicalIssuer(issuer);
        Query query = new Query(Criteria.where(F_TENANT_ID)
                .is(tenantId)
                .and(F_NAME)
                .is(name)
                .and(F_EXT_ISSUER)
                .is(canonicalIssuer)
                .and(F_EXT_EXPECTED_EMAIL)
                .is(normalizedEmail)
                .and(F_EXT_SUBJECT)
                .isNull());
        Update update = new Update()
                .set(F_EXT_SUBJECT, subject)
                .set(F_EXT_EMAIL, email)
                .set(F_EXT_LINKED_AT, now)
                .set(F_EXT_LAST_LOGIN_AT, now);
        UserDocument linked;
        try {
            linked = mongoTemplate.findAndModify(
                    query, update, FindAndModifyOptions.options().returnNew(true), UserDocument.class);
        } catch (DuplicateKeyException e) {
            // The subject was bound to another account a moment ago.
            log.info(
                    "External identity not linked tenantId='{}' name='{}': subject already bound elsewhere",
                    tenantId,
                    name);
            return Optional.empty();
        }
        if (linked != null) {
            log.info("Linked external identity tenantId='{}' name='{}' issuer='{}'", tenantId, name, canonicalIssuer);
        }
        return Optional.ofNullable(linked);
    }

    /**
     * Creates a new account already bound to an external subject — the
     * {@code autoCreate} path. No password, ever; the identity is part of the
     * first write.
     *
     * @throws UserAlreadyExistsException if the name is taken
     * @throws ReservedNameException on a reserved or service-account name
     * @throws ExternalIdentityException if the subject is already bound to an
     *     account
     */
    public UserDocument createExternal(
            String tenantId,
            String name,
            @Nullable String title,
            String provider,
            String issuer,
            String subject,
            String email) {
        if (name.startsWith(SERVICE_ACCOUNT_PREFIX)) {
            throw new ReservedNameException("User name '" + name + "' starts with the reserved '"
                    + SERVICE_ACCOUNT_PREFIX + "' prefix — service accounts cannot sign in externally");
        }
        String canonicalIssuer = ExternalIdentity.canonicalIssuer(issuer);
        Optional<UserDocument> bound = findByExternalSubject(canonicalIssuer, subject);
        if (bound.isPresent()) {
            throw new ExternalIdentityException("The external subject is already bound to user '"
                    + bound.get().getName() + "' in tenant '" + bound.get().getTenantId() + "'");
        }
        Instant now = Instant.now();
        ExternalIdentity identity = ExternalIdentity.builder()
                .provider(provider)
                .issuer(canonicalIssuer)
                .subject(subject)
                .email(email)
                .linkedAt(now)
                .lastLoginAt(now)
                .build();
        UserDocument saved = doCreate(
                tenantId, name, null, title, email, /* loginEnabled */ true, /* serviceAccount */ false, identity);
        log.info("Created user via external sign-in tenantId='{}' name='{}' provider='{}'", tenantId, name, provider);
        return saved;
    }

    private static boolean isLinked(UserDocument user) {
        return user.getExternalIdentity() != null && user.getExternalIdentity().isLinked();
    }

    private static ExternalIdentityException identityConflict(String tenantId, String name, DuplicateKeyException e) {
        log.info(
                "External identity of user '{}' in tenant '{}' collides with another account: {}",
                name,
                tenantId,
                e.getMessage());
        return new ExternalIdentityException("User '" + name + "' in tenant '" + tenantId
                + "' cannot be stored: the address or the external identity is already held by another account");
    }

    /** Stamps the last external sign-in and the address it carried. Best-effort. */
    public void recordExternalLogin(String tenantId, String name, String email) {
        Query query =
                new Query(Criteria.where(F_TENANT_ID).is(tenantId).and(F_NAME).is(name));
        Update update = new Update().set(F_EXT_LAST_LOGIN_AT, Instant.now()).set(F_EXT_EMAIL, email);
        mongoTemplate.updateFirst(query, update, UserDocument.class);
    }

    /**
     * Removes the external identity (prepared or linked). The account is left
     * without any way in until an admin sets a password or prepares it again.
     *
     * @return whether there was an identity to remove
     * @throws UserNotFoundException if the account does not exist
     */
    public boolean unlinkExternalIdentity(String tenantId, String name) {
        UserDocument user = repository
                .findByTenantIdAndName(tenantId, name)
                .orElseThrow(
                        () -> new UserNotFoundException("User '" + name + "' not found in tenant '" + tenantId + "'"));
        if (user.getExternalIdentity() == null) {
            return false;
        }
        user.setExternalIdentity(null);
        repository.save(user);
        log.info("Unlinked external identity tenantId='{}' name='{}'", tenantId, name);
        return true;
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
        Query query =
                new Query(Criteria.where(F_TENANT_ID).is(tenantId).and(F_NAME).is(name));
        Update update = new Update().inc(F_FAILED_ATTEMPTS, 1).set(F_LAST_FAILED, now);
        UserDocument updated = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), UserDocument.class);
        if (updated == null) {
            return;
        }
        if (updated.getFailedLoginAttempts() >= MAX_FAILED_LOGIN_ATTEMPTS) {
            Instant lockedUntil = now.plus(LOCKOUT_DURATION);
            Update lock = new Update().set(F_LOCKED_UNTIL, lockedUntil).set(F_FAILED_ATTEMPTS, 0);
            mongoTemplate.updateFirst(query, lock, UserDocument.class);
            log.warn(
                    "Account locked after {} failed logins tenantId='{}' name='{}' until={}",
                    MAX_FAILED_LOGIN_ATTEMPTS,
                    tenantId,
                    name,
                    lockedUntil);
        }
    }

    /**
     * Atomically clears the failure counter and any lockout for a user —
     * called after a successful login. Best-effort; a no-op if the user does
     * not exist.
     */
    public void resetLoginFailures(String tenantId, String name) {
        Query query =
                new Query(Criteria.where(F_TENANT_ID).is(tenantId).and(F_NAME).is(name));
        Update update =
                new Update().set(F_FAILED_ATTEMPTS, 0).set(F_LOCKED_UNTIL, null).set(F_LAST_FAILED, null);
        mongoTemplate.updateFirst(query, update, UserDocument.class);
    }

    /**
     * Hard-deletes a user, after every {@link UserLifecycleListener} has
     * released what it holds under that name — permission grants above all.
     *
     * <p><b>Why name-keyed state is part of the delete and team memberships are
     * not.</b> A grant is keyed on the <em>username</em>, not on the Mongo id,
     * so it does not disappear with the document — and a login can come back:
     * service accounts follow a naming scheme ({@code _daemon-prod-01}) and
     * human logins get reused. A left-behind TENANT-ADMIN grant would then be
     * inherited, silently, by whoever is created next under the same name,
     * without anybody having granted anything. A team membership carries no
     * authority ({@code ProjectDocument.teamIds} is organisational), so it
     * stays the caller's business — clean it up via
     * {@code TeamService.removeMember}.
     *
     * <p>The listeners run <em>before</em> the document is removed, and their
     * exceptions propagate: a failing grant store aborts the delete rather than
     * leaving the grant without its subject. With no listener present
     * (enterprise governor, or no permission addon at all) this is a plain
     * document delete.
     */
    public void delete(String tenantId, String name) {
        UserDocument user = repository
                .findByTenantIdAndName(tenantId, name)
                .orElseThrow(
                        () -> new UserNotFoundException("User '" + name + "' not found in tenant '" + tenantId + "'"));
        lifecycleListeners.orderedStream().forEach(l -> l.onUserDeleted(tenantId, name));
        repository.delete(user);
        megadodoService.userDeleted(tenantId, name);
        log.info("Deleted user tenantId='{}' name='{}'", tenantId, name);
    }

    /**
     * Renames an account — the login is the business key, so this moves the
     * identity every other entity points at.
     *
     * <p><b>Only ever call this through {@code UserMaintenanceService}.</b> On
     * its own it does half the job: the document says the new name and every
     * session, setting, grant and authored message still says the old one.
     *
     * <p><b>A rename may not change what kind of account this is.</b> A human
     * login stays without the {@code _} prefix and a service account keeps it,
     * because {@code serviceAccount} is a field and the prefix is the
     * convention that describes it — letting the two drift apart produces a
     * human whose name claims otherwise. {@link #RESERVED_VANCE_PREFIX} stays
     * closed either way.
     *
     * <p>No lifecycle listener fires. {@link UserLifecycleListener} exists for
     * an identity coming into existence or going away, and neither happens
     * here: the subject is the same person, so a grant follows the name rather
     * than being revoked. That is the handler's job, not this method's.
     *
     * @throws UserNotFoundException if the account does not exist
     * @throws UserAlreadyExistsException if {@code newName} is taken
     * @throws ReservedNameException if the rename would change the account kind
     *     or reach into the Vance-internal namespace
     */
    public UserDocument rename(String tenantId, String name, String newName) {
        UserDocument user = repository
                .findByTenantIdAndName(tenantId, name)
                .orElseThrow(
                        () -> new UserNotFoundException("User '" + name + "' not found in tenant '" + tenantId + "'"));
        if (repository.existsByTenantIdAndName(tenantId, newName)) {
            throw new UserAlreadyExistsException("User '" + newName + "' already exists in tenant '" + tenantId + "'");
        }
        if (newName.startsWith(RESERVED_VANCE_PREFIX)) {
            throw new ReservedNameException(
                    "User name '" + newName + "' starts with the reserved '" + RESERVED_VANCE_PREFIX + "' prefix");
        }
        if (name.startsWith(SERVICE_ACCOUNT_PREFIX) != newName.startsWith(SERVICE_ACCOUNT_PREFIX)) {
            throw new ReservedNameException("Cannot rename '" + name + "' to '" + newName + "' — the '"
                    + SERVICE_ACCOUNT_PREFIX + "' prefix says whether an account is a"
                    + " service account, and a rename does not change that");
        }
        user.setName(newName);
        UserDocument saved = repository.save(user);
        log.info("Renamed user tenantId='{}' '{}' → '{}'", tenantId, name, newName);
        return saved;
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
     * Thrown when an operation conflicts with an account's external sign-in —
     * a password for an SSO account, preparing a service account, an address
     * already prepared elsewhere.
     */
    public static class ExternalIdentityException extends RuntimeException {
        public ExternalIdentityException(String message) {
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
