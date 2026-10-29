package de.mhus.vance.shared.workspace;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;

/**
 * The git credentials a repository host has, for a tenant and project. Nobody names a
 * credential: the repository's host (and, when the URL carries one, its account) decides.
 * A store keeps each credential under {@link GitCredentialKey#storageKey()} — {@code
 * credential.git.<user>@<host>} — and uses that same key to save and to load.
 */
public interface GitAuthProvider {

    /**
     * @param tenantId  tenant scope of the lookup
     * @param projectId project scope of the lookup
     * @param host      the repository's host, lower-case
     * @param user      the account named in the repository URL ({@code https://user@host/…}),
     *     or {@code null} when it names none
     * @return the credentials for that host, or {@code null} when there are none — the
     *     repository is then accessed publicly
     * @throws GitCredentialException when several accounts have credentials for the host and
     *     {@code user} does not pick one
     */
    @Nullable
    CredentialsProvider provide(
            @Nullable String tenantId, @Nullable String projectId, String host, @Nullable String user);
}
