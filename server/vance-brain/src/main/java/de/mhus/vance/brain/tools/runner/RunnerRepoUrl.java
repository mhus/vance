package de.mhus.vance.brain.tools.runner;

import de.mhus.vance.shared.net.SsrfGuard;
import de.mhus.vance.toolpack.ToolException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where a runner may clone from. The model picks the URL, the runner (inside the tenant's
 * network) does the clone — so the same egress policy as every other brain-side fetch applies
 * ({@link SsrfGuard}), and only http(s) is accepted: a local path or {@code file://} would
 * clone another project's repository off the runner's own disk.
 */
@Component
final class RunnerRepoUrl {

    private final boolean allowLocal;
    private final Consumer<String> egress;

    /**
     * @param allowLocal {@code vance.runner.allow-local-repos}: also accept local paths and
     *     {@code file://} URLs — for development against a runner on the same machine
     */
    @Autowired
    RunnerRepoUrl(@Value("${vance.runner.allow-local-repos:false}") boolean allowLocal) {
        this(allowLocal, SsrfGuard::assertAllowed);
    }

    RunnerRepoUrl(boolean allowLocal, Consumer<String> egress) {
        this.allowLocal = allowLocal;
        this.egress = egress;
    }

    /**
     * @return the URL's host, lower-case; {@code null} for a local source (only when allowed)
     * @throws ToolException when the URL is not an http(s) URL to a host the egress policy allows
     */
    String check(String repoUrl) {
        String url = repoUrl.strip();
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            if (allowLocal && url.startsWith("/")) {
                return "";
            }
            throw new ToolException("'repoUrl' is not a valid URL: " + repoUrl, e);
        }
        String scheme = uri.getScheme();
        if (allowLocal && (scheme == null || "file".equalsIgnoreCase(scheme))) {
            return "";
        }
        if (uri.getHost() == null
                || scheme == null
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new ToolException("'repoUrl' must be an http(s) URL: " + repoUrl);
        }
        try {
            egress.accept(url);
        } catch (SsrfGuard.SsrfException e) {
            throw new ToolException("'repoUrl' refused: " + e.getMessage(), e);
        }
        return uri.getHost().toLowerCase(Locale.ROOT);
    }
}
