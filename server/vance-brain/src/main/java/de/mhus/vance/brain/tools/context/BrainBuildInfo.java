package de.mhus.vance.brain.tools.context;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Build metadata of the running brain: the Maven release version, the
 * build timestamp, and — when the git-commit-id plugin stamped the jar —
 * the exact git revision it was built from.
 *
 * <p>The version and build time come from {@code vance.build.*}, the
 * same filtered properties the WS welcome and the profile REST already
 * report. The git facts come from {@code git.properties} on the
 * classpath, generated at build time by the git-commit-id plugin in
 * {@code vance-brain/pom.xml}. Absence is a legitimate state — a
 * tarball build without a {@code .git} directory produces no file —
 * and resolves to {@link GitFacts#UNKNOWN} rather than failing: a
 * missing stamp must not take the version answer down with it.
 *
 * <p>Why not {@code whoami}: that tool is the *caller's* identity and is
 * primary in every turn. Platform build state is needed rarely and has
 * its own semantic slot; keeping it separate keeps the every-turn
 * output unchanged.
 */
@Component
@Slf4j
public class BrainBuildInfo {

    /**
     * Git facts from a stamped {@code git.properties}. Value object so the
     * tool can surface it verbatim and tests can construct it directly.
     */
    public record GitFacts(String commit, String branch, boolean dirty) {

        /** What a build without a git stamp reports — nothing guessed. */
        public static final GitFacts UNKNOWN = new GitFacts("unknown", "unknown", false);
    }

    private final String version;
    private final String buildTime;
    private final GitFacts git;

    public BrainBuildInfo(
            @Value("${vance.build.version:unknown}") String version,
            @Value("${vance.build.time:unknown}") String buildTime,
            ResourceLoader resourceLoader) {
        this.version = version;
        this.buildTime = buildTime;
        this.git = loadGitFacts(resourceLoader);
    }

    public String version() {
        return version;
    }

    public String buildTime() {
        return buildTime;
    }

    public GitFacts git() {
        return git;
    }

    private static GitFacts loadGitFacts(ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource("classpath:git.properties");
        if (!resource.exists()) {
            // Not an error: tarball / non-repo builds deliberately skip the
            // stamp (failOnNoGitDirectory=false in the plugin config).
            return GitFacts.UNKNOWN;
        }
        try (InputStream in = resource.getInputStream()) {
            Properties props = new Properties();
            props.load(in);
            return parseGitProperties(props);
        } catch (IOException e) {
            log.warn(
                    "BrainBuildInfo: git.properties exists but cannot be read — treating as unstamped: {}",
                    e.toString());
            return GitFacts.UNKNOWN;
        }
    }

    /**
     * Maps a git-commit-id {@code git.properties} onto {@link GitFacts}.
     * Accepts both commit key layouts — the plugin renamed
     * {@code git.commit.id.full} to {@code git.commit.id} in v6 — so a
     * future plugin downgrade fails soft, not hard. A file without any
     * commit or branch is treated as absent ({@link GitFacts#UNKNOWN}).
     */
    static GitFacts parseGitProperties(Properties props) {
        String commit = firstNonBlank(props.getProperty("git.commit.id"), props.getProperty("git.commit.id.full"));
        String branch = props.getProperty("git.branch");
        if (commit == null && branch == null) {
            return GitFacts.UNKNOWN;
        }
        return new GitFacts(
                commit == null ? "unknown" : commit,
                branch == null ? "unknown" : branch,
                Boolean.parseBoolean(props.getProperty("git.dirty")));
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
