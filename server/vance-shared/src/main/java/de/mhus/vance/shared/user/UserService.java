package de.mhus.vance.shared.user;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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

    private final UserRepository repository;

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
     * Creates a user inside {@code tenantId} with {@link UserStatus#ACTIVE}.
     * Throws {@link UserAlreadyExistsException} if a user with the same
     * {@code name} already lives in that tenant.
     */
    public UserDocument create(
            String tenantId,
            String name,
            @Nullable String passwordHash,
            @Nullable String title,
            @Nullable String email) {
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
                .build();
        UserDocument saved = repository.save(user);
        log.info("Created user tenantId='{}' name='{}' id='{}'", saved.getTenantId(), saved.getName(), saved.getId());
        return saved;
    }

    /**
     * Patches mutable fields. {@code name} and {@code tenantId} are
     * immutable; password is set separately via {@link #setPasswordHash}.
     * {@code null} fields mean "leave as is".
     *
     * @throws UserNotFoundException if the user does not exist
     */
    public UserDocument update(
            String tenantId,
            String name,
            @Nullable String title,
            @Nullable String email,
            @Nullable UserStatus status) {
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
        UserDocument saved = repository.save(user);
        log.info("Updated user tenantId='{}' name='{}' status={}",
                saved.getTenantId(), saved.getName(), saved.getStatus());
        return saved;
    }

    /** Replaces the user's password hash. Caller hashes; service stores. */
    public UserDocument setPasswordHash(String tenantId, String name, String passwordHash) {
        UserDocument user = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new UserNotFoundException(
                        "User '" + name + "' not found in tenant '" + tenantId + "'"));
        user.setPasswordHash(passwordHash);
        UserDocument saved = repository.save(user);
        log.info("Reset password tenantId='{}' name='{}'", saved.getTenantId(), saved.getName());
        return saved;
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
}
