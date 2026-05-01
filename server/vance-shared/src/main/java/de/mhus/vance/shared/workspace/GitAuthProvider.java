package de.mhus.vance.shared.workspace;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a {@code credentialAlias} to a JGit
 * {@link CredentialsProvider}. Default implementation returns
 * {@code null} (anonymous access). When a credential store lands,
 * its bean replaces the default and looks up tokens via the alias.
 */
public interface GitAuthProvider {

    /**
     * @param tenantId    tenant scope of the alias lookup
     * @param projectId   project scope of the alias lookup
     * @param credentialAlias key into the credential store; may be {@code null}/blank
     * @return credentials provider, or {@code null} for anonymous access
     */
    @Nullable
    CredentialsProvider provide(@Nullable String tenantId,
                                @Nullable String projectId,
                                @Nullable String credentialAlias);
}
