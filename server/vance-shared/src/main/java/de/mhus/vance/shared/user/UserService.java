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

    /** Thrown by {@link #create(String, String, String, String)} on a duplicate. */
    public static class UserAlreadyExistsException extends RuntimeException {
        public UserAlreadyExistsException(String message) {
            super(message);
        }
    }
}
