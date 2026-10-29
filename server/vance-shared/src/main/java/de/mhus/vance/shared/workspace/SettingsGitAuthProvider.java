package de.mhus.vance.shared.workspace;

import java.util.List;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@link GitAuthProvider} on the credentials kept as settings ({@link GitCredentialStore}): the host
 * selects the entry, the account in the URL picks among several, no entry means public.
 */
@Component
public class SettingsGitAuthProvider implements GitAuthProvider {

    private final GitCredentialStore store;

    public SettingsGitAuthProvider(GitCredentialStore store) {
        this.store = store;
    }

    @Override
    public @Nullable CredentialsProvider provide(
            @Nullable String tenantId, @Nullable String projectId, String host, @Nullable String user) {
        if (tenantId == null) {
            return null;
        }
        List<GitCredentialStore.Located> found = store.locate(tenantId, projectId, host).stream()
                .filter(l -> user == null || l.key().user().equals(user))
                .toList();
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() > 1) {
            String accounts =
                    String.join(", ", found.stream().map(l -> l.key().user()).toList());
            throw new GitCredentialException("There are credentials for several accounts on " + host + " ("
                    + accounts + ") — name the account in the URL, e.g. https://"
                    + found.getFirst().key().user() + "@" + host + "/…, or go anonymously with public");
        }
        GitCredentialStore.Located only = found.getFirst();
        String token = store.token(tenantId, only);
        if (token == null) {
            throw new GitCredentialException(
                    "The credential " + only.key().storageKey() + " cannot be decrypted — store it again");
        }
        return new UsernamePasswordCredentialsProvider(only.key().user(), token);
    }
}
