package de.mhus.vance.shared.workspace;

import java.net.URISyntaxException;
import java.util.Locale;
import org.eclipse.jgit.transport.URIish;
import org.jspecify.annotations.Nullable;

/**
 * The name a git credential is stored under: {@code credential.git.<user>@<host>}, a setting key in the
 * {@code credential.} namespace. A store builds it with
 * {@link #storageKey()} to save and parses it with {@link #parse} to load, so the prefix is added
 * and removed in one place — callers only ever deal in a repository URL.
 *
 * @param user the account, as the forge knows it
 * @param host the forge's host name, lower-case
 */
public record GitCredentialKey(String user, String host) {

    /** Marks a stored entry as a git credential. */
    public static final String PREFIX = "credential.git.";

    public GitCredentialKey {
        if (user.isBlank() || host.isBlank()) {
            throw new IllegalArgumentException("A git credential needs a user and a host");
        }
        host = host.toLowerCase(Locale.ROOT);
    }

    /** {@code credential.git.<user>@<host>}. */
    public String storageKey() {
        return PREFIX + user + "@" + host;
    }

    /** The credential a stored key names, or {@code null} when the key is not a git credential. */
    public static @Nullable GitCredentialKey parse(String storageKey) {
        if (!storageKey.startsWith(PREFIX)) {
            return null;
        }
        String rest = storageKey.substring(PREFIX.length());
        int at = rest.lastIndexOf('@');
        if (at <= 0 || at == rest.length() - 1) {
            return null;
        }
        return new GitCredentialKey(rest.substring(0, at), rest.substring(at + 1));
    }

    /** The host of an http(s), ssh or scp-style git URL; {@code null} for a local path. */
    public static @Nullable String hostOf(String repoUrl) {
        URIish uri = uri(repoUrl);
        return uri == null || uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
    }

    /** The account a git URL names ({@code https://user@host/…}), or {@code null}. */
    public static @Nullable String userOf(String repoUrl) {
        URIish uri = uri(repoUrl);
        return uri == null || uri.getUser() == null || uri.getUser().isBlank() ? null : uri.getUser();
    }

    private static @Nullable URIish uri(String repoUrl) {
        try {
            return new URIish(repoUrl.strip());
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
