package de.mhus.vance.shared.workspace;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Default {@link GitAuthProvider} — anonymous access only. Logs a
 * warning when a {@code credentialAlias} is requested but no real
 * credential store is wired in. A future credential service replaces
 * this bean by registering its own {@code @Primary} {@link GitAuthProvider}.
 */
@Component
@Slf4j
public class NoOpGitAuthProvider implements GitAuthProvider {

    @Override
    public @Nullable CredentialsProvider provide(@Nullable String tenantId,
                                                 @Nullable String projectId,
                                                 @Nullable String credentialAlias) {
        if (StringUtils.isNotBlank(credentialAlias)) {
            log.warn("credentialAlias '{}' requested but no credential store is configured", credentialAlias);
        }
        return null;
    }
}
