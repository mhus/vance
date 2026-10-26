package de.mhus.vance.brain.kit;

import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.net.SsrfGuard;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which kit URLs the brain may clone from or write to. Kit sources, kit exports and
 * catalog scans take their URL from tenant config, and JGit would otherwise accept
 * anything it can open: a local folder or bare path (read — and for an export,
 * write — any directory the brain process can reach), {@code git://},
 * {@code ssh://}, or an http(s) remote on an internal address
 * ({@code planning/multi-tenant-isolation.md} O5).
 *
 * <ul>
 *   <li><b>Remote</b>: only {@code http://} and {@code https://}, and the host must
 *       pass the egress guard ({@link SsrfGuard#assertAllowed(String)}). JGit follows
 *       HTTP redirects on its own, so a redirect is not re-checked.</li>
 *   <li><b>Local folder</b> ({@code file:} or an absolute path): only when the
 *       operator allows it — {@code vance.kit.allow-local-sources}, on by default for
 *       single-tenant and development installs; a shared pod turns it off.</li>
 * </ul>
 */
@Component
public class KitSourceUrlPolicy {

    private final boolean allowLocalSources;

    public KitSourceUrlPolicy(@Value("${vance.kit.allow-local-sources:true}") boolean allowLocalSources) {
        this.allowLocalSources = allowLocalSources;
    }

    /** {@code file:…} or an absolute path. */
    public static boolean isFolderUrl(String url) {
        String trimmed = url.trim();
        return trimmed.startsWith("file:") || trimmed.startsWith("/");
    }

    /**
     * Throws unless the brain may read from (or write to) {@code url}.
     *
     * @throws KitException when the URL form is not allowed or the host is not public
     */
    public void check(String url) {
        if (isFolderUrl(url)) {
            if (!allowLocalSources) {
                throw new KitException(
                        "local kit folders are disabled on this server" + " (vance.kit.allow-local-sources): " + url);
            }
            return;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw new KitException("kit git remotes must be http(s) URLs: " + url);
        }
        try {
            SsrfGuard.assertAllowed(url.trim());
        } catch (SsrfGuard.SsrfException e) {
            throw new KitException("kit source refused: " + e.getMessage(), e);
        }
    }
}
